package com.huning.aerotrace.trace.query;

public class TraceSpanLimitExceededException
        extends RuntimeException {

  public TraceSpanLimitExceededException(
          int maximumSpanCount
  ) {
    super(
            "Trace contains more than "
                    + maximumSpanCount
                    + " spans"
    );
  }
}