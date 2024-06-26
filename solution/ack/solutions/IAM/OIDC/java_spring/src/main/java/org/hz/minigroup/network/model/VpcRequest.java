package org.hz.minigroup.network.model;


public class VpcRequest {
    private String regionId;
    private String cidrBlock;
    private String ipv6CidrBlock;
    private boolean enableIpv6;
    private String vpcName;
    private String userCidr;

    public String getRegionId() {
        return regionId;
    }

    public void setRegionId(String regionId) {
        this.regionId = regionId;
    }

    public String getCidrBlock() {
        return cidrBlock;
    }

    public void setCidrBlock(String cidrBlock) {
        this.cidrBlock = cidrBlock;
    }

    public String getIpv6CidrBlock() {
        return ipv6CidrBlock;
    }

    public void setIpv6CidrBlock(String ipv6CidrBlock) {
        this.ipv6CidrBlock = ipv6CidrBlock;
    }

    public boolean isEnableIpv6() {
        return enableIpv6;
    }

    public void setEnableIpv6(boolean enableIpv6) {
        this.enableIpv6 = enableIpv6;
    }

    public String getVpcName() {
        return vpcName;
    }

    public void setVpcName(String vpcName) {
        this.vpcName = vpcName;
    }

    public String getUserCidr() {
        return userCidr;
    }

    public void setUserCidr(String userCidr) {
        this.userCidr = userCidr;
    }
}
