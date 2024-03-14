# 多账号 Golden Image 方案

[中文](README.md) | [English](README_en.md)

多账号 Golden Image 方案实现了一种企业客户实现云上基础镜像的统一构建、共享和分发的方案，确保在多账号体系下，企业相关应用账号都能够使用安全合规的基础镜像。本代码仓库通过 Terraform 实现该方案的自动化。

## 代码结构

- `golden-image-pipeline`：通过 Terraform 部署的 Golden Image 自动化 Pipeline。
- `golden-image-distribution`：分发 Golden Image 的 Terraform 代码实现。共分为两个步骤：
  - 复制镜像到其他地域。
  - 通过资源共享的共享单元共享镜像给其他账号、资源夹或者整个资源目录。