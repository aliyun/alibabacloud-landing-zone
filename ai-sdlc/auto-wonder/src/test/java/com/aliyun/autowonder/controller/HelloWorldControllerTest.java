package com.aliyun.autowonder.controller;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.*;
class HelloWorldControllerTest {
 @Test void greetingUsesRequestLocaleAndKeepsSuccessEnvelope() {
  try {
   LocaleContextHolder.setLocale(Locale.US); var en=new HelloWorldController().hello();
   assertTrue(en.isSuccess()); assertEquals("0",en.getCode()); assertEquals("",en.getMessage()); assertEquals("Hello from AutoWonder!",en.getData());
   LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE); assertEquals("来自 AutoWonder 的问候！",new HelloWorldController().hello().getData());
  } finally { LocaleContextHolder.resetLocaleContext(); }
 }
}
