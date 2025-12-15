#include <string.h>
#include <pthread.h>
#include <unistd.h>
#include <errno.h>

#include <jni.h>
#include <getopt.h>
#include <signal.h>
#include <setjmp.h>
#include <stdlib.h>

#include "byedpi/error.h"
#include "main.h"
#include "byedpi/params.h"
#include "byedpi/proxy.h"

extern int server_fd;

static int g_proxy_running = 0;
static pthread_mutex_t g_proxy_mutex = PTHREAD_MUTEX_INITIALIZER;

struct params default_params = {
        .await_int = 10,
        .cache_ttl = 100800,
        .ipv6 = 1,
        .resolve = 1,
        .udp = 1,
        .max_open = 512,
        .bfsize = 16384,
        .baddr = {
            .in6 = { .sin6_family = AF_INET6 }
        },
        .laddr = {
            .in = { .sin_family = AF_INET }
        },
        .debug = 0
};

void reset_params(void) {
    clear_params();
    params = default_params;
}

JNIEXPORT jint JNICALL
Java_io_github_dovecoteescapee_byedpi_core_ByeDpiProxy_jniStartProxy(JNIEnv *env, __attribute__((unused)) jobject thiz, jobjectArray args) {
    pthread_mutex_lock(&g_proxy_mutex);
    
    if (g_proxy_running) {
        LOG(LOG_S, "proxy already running");
        pthread_mutex_unlock(&g_proxy_mutex);
        return -1;
    }

    int argc = (*env)->GetArrayLength(env, args);
    if (argc <= 0) {
        LOG(LOG_S, "invalid args count: %d", argc);
        pthread_mutex_unlock(&g_proxy_mutex);
        return -1;
    }

    char **argv = calloc(argc, sizeof(char *));
    if (!argv) {
        LOG(LOG_S, "failed to allocate memory for argv");
        pthread_mutex_unlock(&g_proxy_mutex);
        return -1;
    }

    int error = 0;
    for (int i = 0; i < argc; i++) {
        jstring arg = (jstring) (*env)->GetObjectArrayElement(env, args, i);

        if (!arg) {
            argv[i] = NULL;
            continue;
        }

        const char *arg_str = (*env)->GetStringUTFChars(env, arg, 0);
        if (arg_str) {
            argv[i] = strdup(arg_str);
            if (!argv[i]) {
                LOG(LOG_S, "failed to duplicate string at index %d", i);
                error = 1;
                (*env)->ReleaseStringUTFChars(env, arg, arg_str);
                (*env)->DeleteLocalRef(env, arg);
                break;
            }
            (*env)->ReleaseStringUTFChars(env, arg, arg_str);
        } else {
            argv[i] = NULL;
        }

        (*env)->DeleteLocalRef(env, arg);
    }

    if (error) {
        for (int i = 0; i < argc; i++) {
            if (argv[i]) free(argv[i]);
        }
        free(argv);
        pthread_mutex_unlock(&g_proxy_mutex);
        return -1;
    }
    
    LOG(LOG_S, "starting proxy with %d args", argc);
    reset_params();
    g_proxy_running = 1;
    optind = 1;
    pthread_mutex_unlock(&g_proxy_mutex);

    int result = main(argc, argv);

    LOG(LOG_S, "proxy return code %d", result);
    
    pthread_mutex_lock(&g_proxy_mutex);
    g_proxy_running = 0;
    pthread_mutex_unlock(&g_proxy_mutex);

    for (int i = 0; i < argc; i++) {
        if (argv[i]) free(argv[i]);
    }
    free(argv);

    return result;
}

JNIEXPORT jint JNICALL
Java_io_github_dovecoteescapee_byedpi_core_ByeDpiProxy_jniStopProxy(__attribute__((unused)) JNIEnv *env, __attribute__((unused)) jobject thiz) {
    LOG(LOG_S, "send shutdown to proxy");

    pthread_mutex_lock(&g_proxy_mutex);
    
    if (!g_proxy_running) {
        LOG(LOG_S, "proxy is not running");
        pthread_mutex_unlock(&g_proxy_mutex);
        return -1;
    }

    if (server_fd < 0) {
        LOG(LOG_S, "invalid server_fd: %d", server_fd);
        g_proxy_running = 0;
        pthread_mutex_unlock(&g_proxy_mutex);
        return -1;
    }

    // Устанавливаем флаг остановки в event loop
    pthread_mutex_lock(&g_pool_mutex);
    if (g_event_pool != NULL) {
        g_event_pool->brk = 1;
        LOG(LOG_S, "set pool->brk = 1 to stop event loop (pool=%p)", g_event_pool);
    } else {
        LOG(LOG_S, "g_event_pool is NULL, cannot set brk flag");
    }
    pthread_mutex_unlock(&g_pool_mutex);
    
    // Выполняем shutdown для остановки приема новых соединений
    // НЕ закрываем server_fd здесь - он будет закрыт в start_event_loop()
    // после выхода из loop_event(), чтобы избежать двойного закрытия
    int ret = shutdown(server_fd, SHUT_RDWR);
    if (ret < 0) {
        LOG(LOG_S, "shutdown failed: %s (errno: %d)", strerror(errno), errno);
    } else {
        LOG(LOG_S, "server socket shutdown (fd: %d)", server_fd);
    }
    
    // НЕ закрываем server_fd здесь - он будет закрыт в start_event_loop()
    g_proxy_running = 0;
    pthread_mutex_unlock(&g_proxy_mutex);

    return 0;
}

JNIEXPORT jint JNICALL
Java_io_github_dovecoteescapee_byedpi_core_ByeDpiProxy_jniForceClose(__attribute__((unused)) JNIEnv *env, __attribute__((unused)) jobject thiz) {
    pthread_mutex_lock(&g_proxy_mutex);
    
    LOG(LOG_S, "force closing server socket (fd: %d)", server_fd);

    if (server_fd < 0) {
        LOG(LOG_S, "server socket already closed or invalid (fd: %d)", server_fd);
        g_proxy_running = 0;
        pthread_mutex_unlock(&g_proxy_mutex);
        return 0; // Не ошибка, если уже закрыт
    }

    // Устанавливаем флаг остановки в event loop
    pthread_mutex_lock(&g_pool_mutex);
    if (g_event_pool != NULL) {
        g_event_pool->brk = 1;
        LOG(LOG_S, "set pool->brk = 1 to stop event loop (pool=%p)", g_event_pool);
    } else {
        LOG(LOG_S, "g_event_pool is NULL, cannot set brk flag");
    }
    pthread_mutex_unlock(&g_pool_mutex);

    // Сначала shutdown, затем close
    shutdown(server_fd, SHUT_RDWR);
    
    if (close(server_fd) == -1) {
        LOG(LOG_S, "failed to close server socket (fd: %d): %s (errno: %d)", 
            server_fd, strerror(errno), errno);
    } else {
        LOG(LOG_S, "server socket force closed (fd: %d)", server_fd);
    }
    
    server_fd = -1;
    g_proxy_running = 0;
    pthread_mutex_unlock(&g_proxy_mutex);

    return 0;
}
