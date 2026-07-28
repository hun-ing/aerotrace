package com.huning.aerotrace.ingest.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class OtlpRequestBodyLimitFilter
        extends OncePerRequestFilter {

  private static final String OTLP_TRACES_PATH =
          "/v1/traces";

  private static final int READ_BUFFER_SIZE =
          8 * 1024;

  private final ObjectMapper objectMapper;
  private final int maxRequestBodyBytes;

  public OtlpRequestBodyLimitFilter(
          ObjectMapper objectMapper,
          @Value(
                  "${aerotrace.ingest.max-request-body-bytes:10485760}"
          )
          long maxRequestBodyBytes
  ) {
    if (
            maxRequestBodyBytes <= 0
                    || maxRequestBodyBytes
                    > Integer.MAX_VALUE
    ) {
      throw new IllegalArgumentException(
              "Maximum OTLP request body size "
                      + "must be between 1 and "
                      + Integer.MAX_VALUE
                      + " bytes"
      );
    }

    this.objectMapper = objectMapper;
    this.maxRequestBodyBytes =
            Math.toIntExact(maxRequestBodyBytes);
  }

  @Override
  protected boolean shouldNotFilter(
          HttpServletRequest request
  ) {
    if (
            !HttpMethod.POST.matches(
                    request.getMethod()
            )
    ) {
      return true;
    }

    String targetPath =
            request.getContextPath()
                    + OTLP_TRACES_PATH;

    return !targetPath.equals(
            request.getRequestURI()
    );
  }

  @Override
  protected void doFilterInternal(
          HttpServletRequest request,
          HttpServletResponse response,
          FilterChain filterChain
  ) throws ServletException, IOException {
    long declaredContentLength =
            request.getContentLengthLong();

    if (
            declaredContentLength
                    > maxRequestBodyBytes
    ) {
      writeContentTooLargeResponse(response);
      return;
    }

    final byte[] requestBody;

    try {
      requestBody =
              readRequestBody(request);
    } catch (
            RequestBodyTooLargeIOException exception
    ) {
      writeContentTooLargeResponse(response);
      return;
    }

    CachedBodyHttpServletRequest wrappedRequest =
            new CachedBodyHttpServletRequest(
                    request,
                    requestBody
            );

    filterChain.doFilter(
            wrappedRequest,
            response
    );
  }

  private byte[] readRequestBody(
          HttpServletRequest request
  ) throws IOException {
    int declaredLength =
            request.getContentLength();

    int initialCapacity =
            declaredLength > 0
                    ? Math.min(
                    declaredLength,
                    READ_BUFFER_SIZE
            )
                    : READ_BUFFER_SIZE;

    try (
            ServletInputStream input =
                    request.getInputStream();

            ByteArrayOutputStream output =
                    new ByteArrayOutputStream(
                            initialCapacity
                    )
    ) {
      byte[] buffer =
              new byte[READ_BUFFER_SIZE];

      int totalBytes = 0;
      int readBytes;

      while (
              (
                      readBytes =
                              input.read(buffer)
              ) != -1
      ) {
        if (
                totalBytes
                        > maxRequestBodyBytes
                        - readBytes
        ) {
          throw new RequestBodyTooLargeIOException();
        }

        totalBytes += readBytes;

        output.write(
                buffer,
                0,
                readBytes
        );
      }

      return output.toByteArray();
    }
  }

  private void writeContentTooLargeResponse(
          HttpServletResponse response
  ) throws IOException {
    response.setStatus(
            HttpStatus.CONTENT_TOO_LARGE.value()
    );

    response.setContentType(
            MediaType.APPLICATION_JSON_VALUE
    );

    response.setCharacterEncoding(
            StandardCharsets.UTF_8.name()
    );

    objectMapper.writeValue(
            response.getOutputStream(),
            new OtlpHttpStatusResponse(
                    "OTLP request body exceeds "
                            + "the maximum allowed size: "
                            + maxRequestBodyBytes
                            + " bytes"
            )
    );
  }

  private static final class
  RequestBodyTooLargeIOException
          extends IOException {
  }

  private static final class
  CachedBodyHttpServletRequest
          extends HttpServletRequestWrapper {

    private final byte[] requestBody;

    private CachedBodyHttpServletRequest(
            HttpServletRequest request,
            byte[] requestBody
    ) {
      super(request);
      this.requestBody =
              requestBody.clone();
    }

    @Override
    public ServletInputStream getInputStream() {
      return new CachedBodyServletInputStream(
              requestBody
      );
    }

    @Override
    public BufferedReader getReader() {
      Charset charset =
              resolveCharset();

      return new BufferedReader(
              new InputStreamReader(
                      getInputStream(),
                      charset
              )
      );
    }

    @Override
    public int getContentLength() {
      return requestBody.length;
    }

    @Override
    public long getContentLengthLong() {
      return requestBody.length;
    }

    private Charset resolveCharset() {
      String encoding =
              getCharacterEncoding();

      if (
              encoding == null
                      || encoding.isBlank()
      ) {
        return StandardCharsets.UTF_8;
      }

      return Charset.forName(encoding);
    }
  }

  private static final class
  CachedBodyServletInputStream
          extends ServletInputStream {

    private final ByteArrayInputStream input;

    private CachedBodyServletInputStream(
            byte[] requestBody
    ) {
      this.input =
              new ByteArrayInputStream(
                      requestBody
              );
    }

    @Override
    public int read() {
      return input.read();
    }

    @Override
    public int read(
            byte[] buffer,
            int offset,
            int length
    ) {
      return input.read(
              buffer,
              offset,
              length
      );
    }

    @Override
    public boolean isFinished() {
      return input.available() == 0;
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setReadListener(
            ReadListener readListener
    ) {
      throw new UnsupportedOperationException(
              "Asynchronous request body reading "
                      + "is not supported"
      );
    }
  }
}