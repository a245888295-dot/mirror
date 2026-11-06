// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <unistd.h>

static volatile sig_atomic_t g_running = 1;

static void on_signal(int sig) {
    (void)sig;
    g_running = 0;
}

int main(int argc, char** argv) {
    const char* path = argc > 1 ? argv[1] : "/dev/hidraw0";

    int fd = open(path, O_WRONLY);
    if (fd < 0) {
        perror("hidraw_sender: open");
        return 1;
    }

    signal(SIGTERM, on_signal);
    signal(SIGINT, on_signal);

    unsigned char buf[10];
    while (g_running) {
        size_t got = 0;
        while (got < sizeof(buf)) {
            ssize_t n = read(STDIN_FILENO, buf + got, sizeof(buf) - got);
            if (n <= 0) {
                close(fd);
                return 0;
            }
            got += (size_t)n;
        }

        if (write(fd, buf, sizeof(buf)) != (ssize_t)sizeof(buf)) {
            perror("hidraw_sender: write");
            break;
        }
    }

    close(fd);
    return 0;
}
