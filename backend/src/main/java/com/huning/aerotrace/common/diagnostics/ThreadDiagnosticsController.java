package com.huning.aerotrace.common.diagnostics;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/diagnostics")
public class ThreadDiagnosticsController {

  @GetMapping("/thread")
  public ThreadDetails currentThread() {
    Thread currentThread = Thread.currentThread();

    return new ThreadDetails(
            currentThread.getName(),
            currentThread.isVirtual(),
            currentThread.isDaemon()
    );
  }

  public record ThreadDetails(
          String name,
          boolean virtual,
          boolean daemon
  ) {
  }
}