package com.aliyun.autowonder.auth.federation;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface FederationIdpDao {
    int insert(FederationIdpDO idp);

    int update(FederationIdpDO idp);

    FederationIdpDO findById(@Param("id") Long id);

    List<FederationIdpDO> listAll();

    int deleteById(@Param("id") Long id);
}
