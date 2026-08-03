package com.huning.aerotrace.trace.query;

public class TraceNotFoundException
        extends RuntimeException {

  public TraceNotFoundException() {
    super("Trace was not found");
  }
}