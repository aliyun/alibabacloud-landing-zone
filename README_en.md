# Alibaba Cloud Landing Zone

[中文](./README.md)｜English

A landing zone is a framework that Alibaba Cloud provides for enterprises to migrate business to the cloud. Landing zones help enterprises plan and implement resource structures, access security, network architectures, and security compliance systems in the cloud. This way, enterprises can build secure, efficient, and manageable multi-account cloud environments. 

## Why is the multi-account architecture? 

When enterprises build and deploy applications, a mechanism is required to isolate their resources. This isolation can be achieved by using multiple Alibaba Cloud accounts. An Alibaba Cloud account provides security, access permissions, and billing boundaries for your cloud resources and allows you to achieve resource independence and isolation. For example, by default, users other than your accounts do not have access to your resources. Similarly, the cost of your consumption is apportioned based on your accounts. Although you may start the business migration to the cloud by using only one Alibaba Cloud account, we recommend that you create multiple accounts as your workload increases and becomes more complex. Using a multi-account environment is a best practice and has the following advantages:
*	Rapid innovation: You can assign Alibaba Cloud accounts to innovative business within your company, which can be teams, departments, projects, or applications. An independent account ensures rapid business innovation without being limited by existing processes or rules. 
*	Simplified billing: You can know the cost that each project or team is responsible for by using multiple Alibaba Cloud accounts. This simplifies cost allocation. 
*	Security control: You can use multiple Alibaba Cloud accounts to isolate business that has specific security requirements or must meet strict compliance requirements, such as Multi-Level Protection Scheme (MLPS), General Data Protection Regulation (GDPR), or Health Insurance Privacy and Portability Act (HIPPA). 
*	Flexibility: You can map Alibaba Cloud accounts on corporate workflows to meet the special requirements of different business on operations, regulations, and budgets. 

## Scenarios

Among our customers, many have strong demands for multiple accounts. The follow examples are typical scenarios:
1.	An electronic product customer has created multiple Alibaba Cloud accounts to meet the needs for business isolation and the needs of different teams as its business in the cloud keeps expanding. In addition to one main business account, the customer has dozens of Alibaba Cloud accounts, such as community business accounts, transaction business accounts, and multiple financial business accounts. **With many accounts, the IT operation and maintenance (O&M) team of the customer cannot manage these accounts in a unified manner. They must frequently switch the logon accounts and sometimes even cause accidental operations. **The customer invites multiple Alibaba Cloud accounts to join the resource directory and manages the accounts in a unified manner. The customer also manages accounts in groups by application program, environment, team, or any other business unit that matters based on its business conditions, and systematically builds a tree-shaped multi-account structure to facilitate unified management of multiple accounts. This way, more complex business requirements can be met in the future. 
2.	An Internet enterprise is undergoing rapid business incubation and iterations. It assigns an independent account to newly incubated business so as to meet the requirements of rapid innovation. In this account, attempts are made for various cloud products to meet the needs of rapid business growth and implement independent business settlement at the same time. This provides rapid and innovative O&M service management mechanism for business incubation. 
3.	A customer in the new finance industry, whose subsidiaries have business including financing guarantee, financing consulting, and micro lending, recently launched its globalization plan. Due to the compliance requirements of different countries, the customer needs to pass the requirements of different compliance regulations on third-party audit for business in different regions. ** The customer has achieved audit compliance in different regions by deploying business in multiple accounts. **Because the customer has more than 10 Alibaba Cloud accounts, the O&M team want to configure audit compliance rules in the management account rather than in each Alibaba Cloud account one by one, regardless of whether the audit is during the event or after the event. In addition, because of business security requirements, the customer needs to buy a set of security products for each Alibaba Cloud account. This incurs high cost including high labor cost and causes complex cross-account resource management. The enterprise uses resource directories to manage multiple accounts in a unified manner and uses the trusted services integrated by using resource directories to implement centralized security control. 

## Challenges brought by multiple accounts

The multi-account architecture has obvious advantages, but it also brings certain management challenges to support teams such as security, O&M, and finance. The following are some examples:
-	Finance
  -	Can the enterprise specify a payment account, which can be used to pay the bills of all other accounts?
  -	Can the enterprise specify the credit limit of the account to ensure the bills will not exceed the budget?
  -	Can multiple accounts share the same discounts and invoices be issued for the bills of different accounts in a unified manner?
-	Network
  -	Can the virtual private cloud (VPC) networks among multiple accounts connect to one another?
  -	Can the enterprise configure to manage the Internet traffic in a uniform way?
  -	Can the enterprise configure to enable complete north-south and east-west access control?
-	O&M
  -	Can the enterprise collect and analyze the logs of multiple accounts in a unified way?
  -	Can the enterprise enable consistent control policies for multiple accounts?
  -	Can the enterprise configure unified monitoring and alerts for multiple accounts?
-	......

## How to respond?
In this project, we will classify problems into eight sections and summarize several typical scenario-based solutions in each section. We will include the following standard materials in each solution:
-	A one-page PPT on the solution: includes architecture, advantages and associated products.
-	A descriptive document: describes in detail the principles of the solution and how to deploy this solution.
-	An automation script: usually terraform, if any.
In this process, we will continually turn these solutions into a commercial product. The corresponding product is [Cloud Governance Center](https://governance.console.aliyun.com/).

## Example

1. [Initialize a resource directory](./solution/IAM/function/01-terraform-init-resource-directory). Enable a resource directory for the main account, create a resource folder, and then create a resource account in the folder.
2. [Apply a policy for the resource directory](./solution/IAM/function/02-terraform-control-policy). Create a new access control policy and bind it to the specified resource folder.
3. [Create roles for member accounts](./solution/IAM/function/03-terraform-auto-create-role). Create RAM roles specified by users for the main account and all its member accounts.
4. [Assume different roles across multiple cloud accounts](./solution/IAM/function/04-terraform-multi-roles). Use the Alibaba Cloud account to assume different roles in the member accounts specified by users and print the account UID of each role.

## Solution automation

- [2.1 Configuration of multi-account SSO automation](./solution/IAM/2.1-multi-account-sso): This solution creates user-specified identity providers (IdPs) and RAM roles for the member accounts of the Alibaba Cloud account to implement single sign-on (SSO) in multi-account scenarios.
- [3.1 Enterprise-level centralized audit architecture](./solution/compliance/3.1-actiontrail): This solution helps an enterprise collect and retain audit logs in an accurate and stable manner in a cloud-based IT architecture in which multiple accounts are used.
- [3.2 Multi-account golden image solution](./solution/compliance/3.2-goldenImage): This solution allows enterprises to build, share, and distribute base images in a unified manner based on resource directories to ensure the use of base images is compliant.
- [3.3 Synchronization of cloud configurations to the CMDB of an enterprise](./solution/compliance/3.3-cmdb): This solution supports one-stop collection of full resource configuration data based on resource directories to accelerate the building of the configuration management database (CMDB) owned by an enterprise.

## Feedback

If you encounter any issues, please submit your issues. If you want to participate in the improvement project, please submit a pull request (PR). 

## Participation in development

When you submit a PR, you must agree to Alibaba Contributor License Agreement (CLA). Otherwise, the PR will not be merged. 
