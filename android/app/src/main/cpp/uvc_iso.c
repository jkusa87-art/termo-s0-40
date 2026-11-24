// Odbior strumienia UVC (transfer izochroniczny albo bulk) przez usbfs (ioctl na deskryptorze
// z UsbDeviceConnection.getFileDescriptor()). Java/Kotlin API Androida nie obsluguje
// transferow izochronicznych, wiec ta czesc jest natywna (bulk przy okazji tez tutaj). Sterowanie (probe/commit,
// alt setting, komendy ZOOM) robi Kotlin przez UsbDeviceConnection.
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <stdio.h>
#include <time.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <android/log.h>

#define TAG "uvciso"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define NUM_URBS 16
#define PKTS_PER_URB 32

typedef struct {
    int fd, ep, pkt_size, frame_size, bulk;
    volatile int running, done;
    pthread_t thread;
    struct usbdevfs_urb *urbs[NUM_URBS];
    unsigned char *assemble; int asm_len; int last_fid; int asm_bad;
    unsigned char *ready; int ready_seq;
    pthread_mutex_t mtx; pthread_cond_t cond;
    // statystyki
    long frames_ok, frames_bad, pkts, pkt_err;
} ctx_t;

static ctx_t g;

static struct usbdevfs_urb *alloc_urb(void) {
    if (g.bulk) {   // jeden URB = jeden payload UVC (rozmiar = dwMaxPayloadTransferSize)
        struct usbdevfs_urb *b = calloc(1, sizeof(struct usbdevfs_urb));
        b->type = USBDEVFS_URB_TYPE_BULK;
        b->endpoint = g.ep;
        b->buffer_length = g.pkt_size;
        b->buffer = malloc(b->buffer_length);
        return b;
    }
    size_t sz = sizeof(struct usbdevfs_urb) + PKTS_PER_URB * sizeof(struct usbdevfs_iso_packet_desc);
    struct usbdevfs_urb *u = calloc(1, sz);
    u->type = USBDEVFS_URB_TYPE_ISO;
    u->endpoint = g.ep;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    u->buffer_length = g.pkt_size * PKTS_PER_URB;
    u->buffer = malloc(u->buffer_length);
    u->number_of_packets = PKTS_PER_URB;
    return u;
}

static int submit(struct usbdevfs_urb *u) {
    if (!g.bulk) for (int i = 0; i < PKTS_PER_URB; i++) {
        u->iso_frame_desc[i].length = g.pkt_size;
        u->iso_frame_desc[i].actual_length = 0;
        u->iso_frame_desc[i].status = 0;
    }
    u->status = 0; u->actual_length = 0;
    return ioctl(g.fd, USBDEVFS_SUBMITURB, u);
}

static void finish_frame(void) {
    if (g.asm_len == g.frame_size && !g.asm_bad) {
        pthread_mutex_lock(&g.mtx);
        memcpy(g.ready, g.assemble, g.frame_size);
        g.ready_seq++;
        pthread_cond_broadcast(&g.cond);
        pthread_mutex_unlock(&g.mtx);
        g.frames_ok++;
    } else if (g.asm_len > 0) {
        g.frames_bad++;
    }
    g.asm_len = 0; g.asm_bad = 0;
}

static void handle_packet(const unsigned char *p, int len) {
    if (len < 2) return;
    int hl = p[0], info = p[1];
    if (hl < 2 || hl > len) return;
    int fid = info & 0x01;
    if (g.last_fid >= 0 && fid != g.last_fid && g.asm_len > 0) finish_frame();
    g.last_fid = fid;
    if (info & 0x40) g.asm_bad = 1;          // bit ERR
    int n = len - hl;
    if (n > 0) {
        if (g.asm_len + n <= g.frame_size) memcpy(g.assemble + g.asm_len, p + hl, n);
        else g.asm_bad = 1;
        g.asm_len += n;
    }
    if (info & 0x02) finish_frame();         // bit EOF
}

static void *reader(void *arg) {
    (void)arg;
    int outstanding = 0;
    for (int i = 0; i < NUM_URBS; i++) {
        if (submit(g.urbs[i]) == 0) outstanding++;
        else LOGE("submit %d: %s", i, strerror(errno));
    }
    LOGI("start: %d URB %s, pkt %d, frame %d", outstanding, g.bulk ? "bulk" : "iso", g.pkt_size, g.frame_size);
    while (outstanding > 0) {
        struct usbdevfs_urb *u = NULL;
        if (ioctl(g.fd, USBDEVFS_REAPURB, &u) < 0) {
            if (errno == EINTR) continue;
            LOGE("reap: %s", strerror(errno));
            break;
        }
        outstanding--;
        if (!g.running) continue;
        unsigned char *b = u->buffer;
        if (g.bulk) {
            g.pkts++;
            if (u->status == 0) handle_packet(b, u->actual_length); else g.pkt_err++;
            if (submit(u) == 0) outstanding++; else LOGE("resubmit: %s", strerror(errno));
            continue;
        }
        int off = 0;
        for (int i = 0; i < u->number_of_packets; i++) {
            struct usbdevfs_iso_packet_desc *d = &u->iso_frame_desc[i];
            g.pkts++;
            if (d->status == 0) handle_packet(b + off, d->actual_length);
            else g.pkt_err++;
            off += d->length;
        }
        if (submit(u) == 0) outstanding++;
        else LOGE("resubmit: %s", strerror(errno));
    }
    LOGI("stop: ok=%ld bad=%ld pkts=%ld err=%ld", g.frames_ok, g.frames_bad, g.pkts, g.pkt_err);
    pthread_mutex_lock(&g.mtx);
    g.running = 0; g.done = 1;
    pthread_cond_broadcast(&g.cond);
    pthread_mutex_unlock(&g.mtx);
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_pl_termo_s040_UvcIso_nativeStart(JNIEnv *env, jclass c, jint fd, jint ep, jint pkt, jint frame, jboolean bulk) {
    if (g.running) return JNI_FALSE;
    memset(&g, 0, sizeof(g));
    g.fd = fd; g.ep = ep; g.pkt_size = pkt; g.frame_size = frame; g.last_fid = -1; g.bulk = bulk ? 1 : 0;
    g.assemble = malloc(frame); g.ready = malloc(frame);
    pthread_mutex_init(&g.mtx, NULL); pthread_cond_init(&g.cond, NULL);
    for (int i = 0; i < NUM_URBS; i++) g.urbs[i] = alloc_urb();
    g.running = 1;
    if (pthread_create(&g.thread, NULL, reader, NULL) != 0) { g.running = 0; return JNI_FALSE; }
    return JNI_TRUE;
}

// Czeka na nowa klatke (max timeoutMs), kopiuje do bufora direct. Zwraca numer klatki lub -1.
JNIEXPORT jint JNICALL
Java_pl_termo_s040_UvcIso_nativeReadFrame(JNIEnv *env, jclass c, jobject buf, jint lastSeq, jint timeoutMs) {
    unsigned char *dst = (*env)->GetDirectBufferAddress(env, buf);
    if (!dst || (*env)->GetDirectBufferCapacity(env, buf) < g.frame_size) return -1;
    struct timespec ts; clock_gettime(CLOCK_REALTIME, &ts);
    ts.tv_sec += timeoutMs / 1000; ts.tv_nsec += (long)(timeoutMs % 1000) * 1000000L;
    if (ts.tv_nsec >= 1000000000L) { ts.tv_sec++; ts.tv_nsec -= 1000000000L; }
    int seq = -1;
    pthread_mutex_lock(&g.mtx);
    while (g.running && g.ready_seq == lastSeq) {
        if (pthread_cond_timedwait(&g.cond, &g.mtx, &ts) == ETIMEDOUT) break;
    }
    if (g.ready_seq != lastSeq) { memcpy(dst, g.ready, g.frame_size); seq = g.ready_seq; }
    pthread_mutex_unlock(&g.mtx);
    return seq;
}

JNIEXPORT jstring JNICALL
Java_pl_termo_s040_UvcIso_nativeStats(JNIEnv *env, jclass c) {
    char s[160];
    snprintf(s, sizeof s, "ok=%ld bad=%ld pkts=%ld err=%ld", g.frames_ok, g.frames_bad, g.pkts, g.pkt_err);
    return (*env)->NewStringUTF(env, s);
}

JNIEXPORT void JNICALL
Java_pl_termo_s040_UvcIso_nativeStop(JNIEnv *env, jclass c) {
    if (!g.running && !g.thread) return;
    g.running = 0;
    // Watek mogl wlasnie ponownie wyslac URB - odrzucaj w petli, az skonczy.
    for (int k = 0; k < 100 && !g.done; k++) {
        for (int i = 0; i < NUM_URBS; i++) if (g.urbs[i]) ioctl(g.fd, USBDEVFS_DISCARDURB, g.urbs[i]);
        usleep(10000);
    }
    pthread_join(g.thread, NULL);
    for (int i = 0; i < NUM_URBS; i++) if (g.urbs[i]) { free(g.urbs[i]->buffer); free(g.urbs[i]); g.urbs[i] = NULL; }
    free(g.assemble); free(g.ready); g.assemble = g.ready = NULL;
    g.thread = 0;
}
