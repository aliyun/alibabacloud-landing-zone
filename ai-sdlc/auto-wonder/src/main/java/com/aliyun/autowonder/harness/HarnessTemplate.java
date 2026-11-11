package com.aliyun.autowonder.harness;

import java.util.List;

/** 服务端可信发布包中的模板快照；模板正文只来自资源文件，不接受调用方输入。 */
public final class HarnessTemplate {

    private final String key;
    private final String version;
    private final String name;
    private final String description;
    private final String sha256;
    private final int schemaVersion;
    private final List<HarnessVariable> variables;
    private final List<HarnessAgent> agents;
    private final List<HarnessSkill> skills;
    private final String squadName;
    private final String squadDescription;

    public HarnessTemplate(String key, String version, String name, String description, String sha256,
            int schemaVersion, List<HarnessVariable> variables, List<HarnessAgent> agents,
            List<HarnessSkill> skills, String squadName, String squadDescription) {
        this.key = key;
        this.version = version;
        this.name = name;
        this.description = description;
        this.sha256 = sha256;
        this.schemaVersion = schemaVersion;
        this.variables = List.copyOf(variables);
        this.agents = List.copyOf(agents);
        this.skills = List.copyOf(skills);
        this.squadName = squadName;
        this.squadDescription = squadDescription;
    }

    public String getKey() { return key; }

    public String getVersion() { return version; }

    public String getName() { return name; }

    public String getDescription() { return description; }

    public String getSha256() { return sha256; }

    public int getSchemaVersion() { return schemaVersion; }

    public List<HarnessVariable> getVariables() { return variables; }

    public List<HarnessAgent> getAgents() { return agents; }

    public List<HarnessSkill> getSkills() { return skills; }

    public String getSquadName() { return squadName; }

    public String getSquadDescription() { return squadDescription; }

    public HarnessSkill skill(String key) {
        return skills.stream().filter(skill -> skill.key().equals(key)).findFirst().orElse(null);
    }

    public HarnessAgent agent(String roleCode) {
        return agents.stream().filter(agent -> agent.roleCode().equals(roleCode)).findFirst().orElse(null);
    }

    /** 初始化固定保留全部变量，因此变量数直接由模板声明决定。 */
    public List<String> variableTokens() {
        return variables.stream().map(HarnessVariable::token).toList();
    }
}
