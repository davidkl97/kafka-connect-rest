package com.tm.kafka.connect.rest.http.executor;


import okhttp3.*;
import org.apache.kafka.common.Configurable;
import org.apache.kafka.connect.errors.RetriableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.cert.CertificateException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class OkHttpRequestExecutor implements RequestExecutor, Configurable {

  private static Logger log = LoggerFactory.getLogger(OkHttpRequestExecutor.class);

  private OkHttpClient client;
  private OkHttpClient.Builder builder;


  @Override
  public void configure(Map<String, ?> props) {
    final OkHttpRequestExecutorConfig config = new OkHttpRequestExecutorConfig(props);

    builder = new OkHttpClient.Builder()
      .connectionPool(new ConnectionPool(config.getMaxIdleConnections(), config.getKeepAliveDuration(), TimeUnit.MILLISECONDS))
      .connectTimeout(config.getConnectionTimeout(), TimeUnit.MILLISECONDS)
      .readTimeout(config.getReadTimeout(), TimeUnit.MILLISECONDS);
    builder = this.configureToIgnoreCertificate(builder);
    client = builder.build();
  }

  private OkHttpClient.Builder configureToIgnoreCertificate(OkHttpClient.Builder builder) {
    try {

      // Create a trust manager that does not validate certificate chains
      final TrustManager[] trustAllCerts = new TrustManager[]{
        new X509TrustManager() {
          @Override
          public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType)
            throws CertificateException {
          }

          @Override
          public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType)
            throws CertificateException {
          }

          @Override
          public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return new java.security.cert.X509Certificate[]{};
          }
        }
      };

      // Install the all-trusting trust manager
      final SSLContext sslContext = SSLContext.getInstance("SSL");
      sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
      // Create an ssl socket factory with our all-trusting manager
      final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

      builder.sslSocketFactory(sslSocketFactory, (X509TrustManager) trustAllCerts[0]);
      builder.hostnameVerifier(new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
          return true;
        }
      });
    } catch (Exception e) {
    }
    return builder;
  }

  @Override
  public com.tm.kafka.connect.rest.http.Response execute(com.tm.kafka.connect.rest.http.Request request) throws IOException {
    okhttp3.Request.Builder builder = new okhttp3.Request.Builder()
      .url(createUrl(request.getUrl(), request.getParameters()))
      .headers(Headers.of(headersToArray(request.getHeaders())));

    if ("GET".equalsIgnoreCase(request.getMethod())) {
      builder.get();
    } else {
      builder.method(request.getMethod(), RequestBody.create(
        MediaType.parse(request.getHeaders().getOrDefault("Content-Type", "")),
        request.getBody())
      );
    }

    okhttp3.Request okRequest = builder.build();
    log.trace("Making request to: " + request);

    try (okhttp3.Response okResponse = client.newCall(okRequest).execute()) {

      return new com.tm.kafka.connect.rest.http.Response(
        okResponse.code(),
        okResponse.headers().toMultimap(),
        okResponse.body() != null ? okResponse.body().string() : null
      );
    } catch (IOException e) {
      throw new RetriableException(e.getMessage(), e);
    }
  }

  private String createUrl(String url, Map<String, String> parameters) {
    if (parameters == null || parameters.isEmpty()) {
      return url;
    }

    String format = url.endsWith("?") ? "%s&%s" : "%s?%s";
    return String.format(format, url, parametersToString(parameters));
  }

  private String parametersToString(final Map<String, String> parameters) {
    return parameters.entrySet().stream()
      .map(e -> parameterToString(e.getKey(), e.getValue()))
      .collect(Collectors.joining("&"));
  }

  private String parameterToString(final String key, final String value) {
    try {
      return key.trim() + "=" + URLEncoder.encode(value.trim(), "UTF-8");
    } catch (UnsupportedEncodingException ex) {
      // This should never happen!
      log.warn("Unable to encode URL parameter as UTF-8 (UTF-8 not supported)");
      return key.trim() + "=" + value.trim();
    }
  }

  private String[] headersToArray(final Map<String, String> headers) {
    return headers.entrySet()
      .stream()
      .flatMap(e -> Stream.of(e.getKey(), e.getValue()))
      .toArray(String[]::new);
  }
}
