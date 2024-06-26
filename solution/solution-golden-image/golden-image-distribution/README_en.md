# Distributing Golden Image

[中文](README.md) | [English](README_en.md)

## Prerequisites

1. Set up a resource directory and establish the corresponding mutli-account structure.

## Usage

Distributing the Golden Image involves two steps:

1. Go to `step1-copy-golden-image` and copy the source image to other regions. The copied image will have the same name and tag as the source image.
2. Go to `step2-share-golden-image` and use resource sharing units to share the image with other accounts. First, create a resource sharing unit. Then, bind the desired targets to the sharing unit. The sharing targets can be any account, organizational unit, or the entire resource directory structure. Finally, add the desired image to the sharing unit to complete the sharing operation.

## Potential Issues

The image building process may take too long, resulting in execution failure. The current timeout set for the alicloud provider is 10 minutes.