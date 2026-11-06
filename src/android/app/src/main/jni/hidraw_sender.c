// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#define REPORT_SIZE 10

/* The HID_ID line inside a hidraw node's device/uevent looks like
 * "0005:0000057E:00002009" (Bluetooth) or "0003:0000057E:00002009" (USB),
 * where the middle field is the vendor id. */
static const char NINTENDO_VID[] = "0000057E";

static volatile sig_atomic_t g_running = 1;

static void on_signal(int sig) {
    (void)sig;
    g_running = 0;
}

static int is_nintendo_hidraw(const char* name) {
    char uevent[256];
    snprintf(uevent, sizeof(uevent), "/sys/class/hidraw/%s/device/uevent", name);

    FILE* file = fopen(uevent, "r");
    if (!file) {
        return 0;
    }

    char line[256];
    int matched = 0;
    while (fgets(line, sizeof(line), file)) {
        if (strncmp(line, "HID_ID=", 7) == 0 && strstr(line, NINTENDO_VID)) {
            matched = 1;
            break;
        }
    }

    fclose(file);
    return matched;
}

/* Picks the first hidraw node that belongs to a Nintendo controller. */
static int find_nintendo_hidraw(char* out, size_t out_size) {
    DIR* dir = opendir("/sys/class/hidraw");
    if (!dir) {
        fprintf(stdout, "ERROR opendir /sys/class/hidraw: %s\n", strerror(errno));
        fflush(stdout);
        return 0;
    }

    int found = 0;
    struct dirent* entry;
    while ((entry = readdir(dir)) != NULL) {
        if (strncmp(entry->d_name, "hidraw", 6) != 0) {
            continue;
        }
        if (!is_nintendo_hidraw(entry->d_name)) {
            continue;
        }
        snprintf(out, out_size, "/dev/%s", entry->d_name);
        found = 1;
        break;
    }

    closedir(dir);
    return found;
}

int main(int argc, char** argv) {
    char node[64];

    if (argc > 1) {
        snprintf(node, sizeof(node), "%s", argv[1]);
    } else if (!find_nintendo_hidraw(node, sizeof(node))) {
        /* The app retries, so a missing controller is not worth failing on:
         * report it and let the caller decide when to come back. */
        fprintf(stdout, "NOTFOUND no Nintendo hidraw node\n");
        fflush(stdout);
        return 2;
    }

    int fd = open(node, O_WRONLY);
    if (fd < 0) {
        fprintf(stdout, "ERROR open %s: %s\n", node, strerror(errno));
        fflush(stdout);
        return 1;
    }

    fprintf(stdout, "READY %s\n", node);
    fflush(stdout);

    signal(SIGTERM, on_signal);
    signal(SIGINT, on_signal);
    signal(SIGPIPE, SIG_IGN);

    unsigned char buffer[REPORT_SIZE];
    while (g_running) {
        size_t got = 0;
        while (got < sizeof(buffer)) {
            ssize_t count = read(STDIN_FILENO, buffer + got, sizeof(buffer) - got);
            if (count <= 0) {
                close(fd);
                return 0;
            }
            got += (size_t)count;
        }

        size_t written = 0;
        while (written < sizeof(buffer)) {
            ssize_t count = write(fd, buffer + written, sizeof(buffer) - written);
            if (count < 0) {
                fprintf(stdout, "ERROR write %s: %s\n", node, strerror(errno));
                fflush(stdout);
                close(fd);
                return 1;
            }
            written += (size_t)count;
        }
    }

    close(fd);
    return 0;
}
