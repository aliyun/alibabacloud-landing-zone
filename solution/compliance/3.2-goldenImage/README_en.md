# Multi-account golden image solution

[中文](./README.md)｜English

## Solution overview

This solution uses Terraform for automated execution and is completed in two phases. In the first phase, an Elastic Compute Service (ECS) instance is created and used to generate an ECS image. If an ECS instance already exists, you can skip this phase. Then, you can set the user-data field to run a custom script to perform operations in the image. You can also run the Cloud Assistant command to perform operations in the image. For details about the Terraform script used to create an ECS instance, you can refer to the content in the `step1-create-ecs` folder. In the second phase, a custom image is created based on the ECS instance ID which can be obtained in phase 1 or be queried in the ECS console. Then, the image is sent to all member accounts. At the same time, the access control policy is applied to the specified resource folders, in which member accounts can use only the golden image to create an ECS instance. 

## Prerequisites

-	Terraform is installed in the current environment. 
-	The resource directory is enabled for the management account. 
-	The AccessKey pair of the RAM user is obtained. The RAM user is granted permissions to assume RAM roles. To meet these requirements, create a RAM user in your management account and generate an AccessKey pair for the RAM user. Then, attach the `AliyunResourceDirectoryFullAccess`, `AliyunECSFullAccess`, and `AliyunVPCFullAccesss` policies to the RAM user. 

## Procedure

1. Step 1 Download the code package and decompress it to a directory. The directory structure is as follows:
```
├── step1-create-ecs               // Phase 1: Create an ECS instance.
│   ├── main.tf                   // The entry file used in phase 1. Do not modify it.
│   ├── settings.tfvars          // The configuration file. You can modify it as required.
│   └── variables.tf              // The definitions of variables used in phase 1. Do not modify them.
│
└── step2-distribute-golden-image // Phase 2: Send the golden image.
    ├── main.tf                   // The entry file used in phase 2. Do not modify it.
    ├── settings.tfvars             // The configuration file. You can modify it as required.
    └── variables.tf              // The definitions of variables used in phase 2. Do not modify them.
```
2. Step 2 If you use an existing ECS instance as an image, you can skip Step 1 and obtain the ID of the ECS instance from the console. To create an image by using a new ECS instance, you can use the terraform script in `step1-create-ecs` folder and modify the `user_data` field to customize the ECS instance. 
3. Step 3 Perform the following operations in phase 1:
  -	Go to the `step1-create-ecs` folder, open the `settings.tfvars` file, and modify the configuration items in the file based on the comments.
  -	Run the `terraform plan -var-file=settings.tfvars` command and check whether any error occurs. If an error occurs, check whether the configuration items are valid in Step 2. 
  -	Run the `terraform apply -var-file=settings.tfvars` command. Enter `yes` after the self-check succeeds. When the command is executed successfully, an ECS instance is created in the specified region in the management account, and the ID of the ECS instance is displayed in the console. 
  ![GoldenImage-terraform](../img/GoldenImage-step1-apply.png)
4. Step 4 Perform the following operations in phase 2:
  -	Go to the `step2-distribute-golden-image` folder, open the `settings.tfvars` file, and modify the configuration items in the file based on the remarks.
    -	The AccessKey ID and AccessKey secret of the management account.
    -	The region where the ECS instance is located. The image is also sent to the region of the member account.
    -	The parameters used to create the image: Enter the ECS instance ID in phase 1 in the `ecs_instance_id` field. The `platform` field specifies the operating system platform of the system disk after the data disk snapshot is specified as the data source of the system disk used to create the image. 
    ```
    # The ID of the ECS instance used to create an image
    ecs_instance_id = "i-bp13mgt0kaaj0gtx3m16"
    ```

    -	The ID of the resource folder to which the access control policy is applied.
  -	Run the `terraform plan -var-file=settings.tfvars` command and check whether any error occurs. If an error occurs, check whether the configuration items are valid in Step 2. 
  -	Run the `terraform apply -var-file=settings.tfvars` command. Enter `yes` after the self-check succeeds. 
  ![GoldenImage-step2-apply](../img/GoldenImage-step2-apply.png)
  -	When the command is executed successfully, an image named `golden_image_%{currentTime}` is created in the management account, and the result shows that the image is shared. In the Resource Management console, the Control Policy page displays that the `prohibit-other-images-except-golden-image` policy is created and bound to the corresponding resource folder.  
  -	The member accounts can confirm to receive the image on the Resources Shared To Me page. 
