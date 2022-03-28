## Notes on Backend
The 'key' field in backend configuration at each step of the pipeline must be different. Otherwise, overwriting will occur.

For example, the step of creating an IDP is completed, and the generated state file will have IDP information.
If the 'key' field of the next step X is configured the same as the IDP step, after execution,  because of the IDP Terraform code is not included in step X,
Terraform thinks that the final state description of the resource has been changed, and the IDP will be overwritten and deleted.