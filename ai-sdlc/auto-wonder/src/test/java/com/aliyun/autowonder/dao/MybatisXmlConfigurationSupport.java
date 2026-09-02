package com.aliyun.autowonder.dao;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

// Builds a real MyBatis Configuration from production mapper XMLs, mirroring mybatis.mapper-locations in application.yml.
public final class MybatisXmlConfigurationSupport {

    private MybatisXmlConfigurationSupport() {
    }

    public static Configuration loadConfigurationFromMapperXml() {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setDatabaseId("autowonder-source-aware");
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources("classpath*:mapping/*Dao.xml");
            for (Resource resource : resources) {
                try (InputStream in = resource.getInputStream()) {
                    new XMLMapperBuilder(in, configuration, resource.getDescription(),
                            configuration.getSqlFragments()).parse();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load mapper XML resources", e);
        }
        return configuration;
    }
}
