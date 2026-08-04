package com.aliyun.autowonder.integration.dingtalk;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Configuration
public class DingTalkOutboundSenderConfig {

    @Bean
    public DingTalkOutboundSender dingTalkOutboundSender() {
        return new DingTalkOutboundSender(new OkHttpExchange());
    }

    static final class OkHttpExchange implements DingTalkOutboundSender.HttpExchange {

        private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

        private final OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();

        @Override
        public String post(String url, String jsonBody, Map<String, String> headers) {
            Request request = new Request.Builder()
                    .url(url)
                    .headers(Headers.of(headers == null ? Map.of() : headers))
                    .post(RequestBody.create(jsonBody, JSON_MEDIA))
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                ResponseBody body = response.body();
                String text = body == null ? "" : body.string();
                if (!response.isSuccessful()) {
                    throw new DingTalkHttpException(response.code(), text);
                }
                return text;
            } catch (IOException e) {
                throw new IllegalStateException("DingTalk request transport failed", e);
            }
        }
    }
}
