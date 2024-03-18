# Multi-account Golden Image Solution

[中文](README.md) | [English](README_en.md)

The Multi-account Golden Image Solution provides an approach for enterprise customers to achieve unified building, sharing, and distribution of cloud-based golden images. This ensures that all enterprise-related application accounts can utilize secure and compliant golden images within a multi-account architecture. This code repository utilizes Terraform to automate the implementation of this solution.

## Architecture

![Architecture](https://img.alicdn.com/imgextra/i4/O1CN01LYDP4S1kMSAWcZNkX_!!6000000004669-0-tps-2580-2540.jpg)

* Enterprises build images in a separate shared service account, unified control, permissions can be isolated from the application account.
* Through the control policy, limit the image ID that can be used by the application account to prevent the application account from using non-compliant images.
* Based on resource sharing, images can be quickly distributed to all application accounts in batches to improve efficiency.

## Code Structure

- `golden-image-pipeline`: Terraform code for deploying the Golden Image automation pipeline which is used to build golden images using OOS.
- `golden-image-distribution`: Terraform code for distributing the Golden Image to other accounts within your orgnization. It consists of two steps:
  - Copying the image to other regions where your workloads may deploy to.
  - Sharing the image with other accounts, organizational units, or the entire resource directory using resource sharing units.