# Community Squad Template Seed

## Scope

Community installations seed four system `squad_template` rows from
`docs/autowonder-community-templates.sql`:

1. `独立开发者` with one developer.
2. `开发+评审双人组` with developer and code reviewer.
3. `标准研发交付小队` with developer, reviewer, and tester.
4. `全链路研发协作小队` with requirement clarification, project management,
   development, review, testing, conflict resolution, and DBA roles.

The first three templates are community-safe exports of the verified development
templates. The seven-role template is based on the verified AutoWonder
self-iteration team but contains no project, organization, internal domain,
database endpoint, credential, or source-system identity.

## Data Contract

- All seeded templates have `tenant_id = NULL`, `status = ACTIVE`, valid JSON,
  and stable names. The seed updates a matching system template by name or
  inserts it when absent; it never depends on a fixed database ID.
- Every agent has a unique generic `roleCode`, community-neutral SOUL and AGENT
  content, and a non-empty SDLC with at least one step.
- Development, review, and testing form the normal delivery chain. Requirement
  clarification, project management, conflict resolution, and DBA are invoked
  only when needed and return control to a human where their risk boundary
  requires it.
- External integrations use only `{{SOURCE_REPOSITORY}}`, `{{CODE_PLATFORM}}`,
  `{{DEPLOYMENT_PLATFORM}}`, `{{DATABASE_HOST}}`, `{{DATABASE_NAME}}`, and
  `{{DATABASE_USER_ENV}}` placeholders.
- Template application retains the existing behavior of granting generated
  agents write access to the organization's repositories.

## Deployment Contract

The deployment Skill packages and verifies the JAR, schema SQL, and template seed
SQL. Cloud Assistant transfers all three artifacts to the same versioned ECS
release directory.

During the existing database subphase, deployment imports the schema first and
then the template seed. `.database.imported` remains the schema checkpoint;
`.database.templatesImported` is the template checkpoint. A missing template
checkpoint on an older manifest runs only the idempotent template seed.

The database phase completes only after the schema postcheck and all four named
system templates are present, active, and valid JSON. Missing artifacts, checksum
failure, SQL failure, invalid content, or leaked environment-specific data stops
the phase without marking it initialized.

## Verification

Automated contracts parse all four payloads, assert agent counts of 1, 2, 3, and
7, validate unique role codes and non-empty SDLC steps, reject internal or secret
values, and verify build, transfer, import order, resume behavior, and database
postconditions. Full backend, frontend, and deployment Skill gates run before
Community and GitHub branches are pushed.
