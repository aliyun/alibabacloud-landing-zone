package com.aliyun.autowonder.auth.filter;

import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.auth.session.SessionService;
import com.aliyun.autowonder.org.OrgMemberDao;
import com.aliyun.autowonder.user.UserDao;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuthFilterConfig {

    @Bean
    public FilterRegistrationBean<AuthFilter> authFilterRegistration(
            JwtService jwtService, SessionService sessionService,
            OrgMemberDao orgMemberDao, UserDao userDao) {
        FilterRegistrationBean<AuthFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new AuthFilter(jwtService, sessionService, orgMemberDao, userDao));
        reg.addUrlPatterns("/api/*");
        reg.setOrder(1);
        return reg;
    }
}
