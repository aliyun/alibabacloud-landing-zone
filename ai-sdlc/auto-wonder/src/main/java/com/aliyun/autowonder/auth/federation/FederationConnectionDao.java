package com.aliyun.autowonder.auth.federation;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface FederationConnectionDao {
    int insert(FederationConnectionDO connection);

    int update(FederationConnectionDO connection);

    FederationConnectionDO findById(@Param("id") Long id);

    FederationConnectionDO findByName(@Param("name") String name);

    List<FederationConnectionDO> listAll();

    List<FederationConnectionDO> listByEnabled(@Param("enabled") int enabled);

    long countByIdp(@Param("idpId") Long idpId);

    int deleteById(@Param("id") Long id);
}
