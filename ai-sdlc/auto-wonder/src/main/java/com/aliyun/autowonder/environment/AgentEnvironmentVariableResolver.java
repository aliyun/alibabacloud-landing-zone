package com.aliyun.autowonder.environment;

import com.aliyun.autowonder.agent.AgentEnvironmentVariableRefDao;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Resolves a complete, short-lived environment snapshot for one frozen agent version. */
@Service
public class AgentEnvironmentVariableResolver {
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final AgentEnvironmentVariableRefDao refDao;
    private final SecretCrypto secretCrypto;

    public AgentEnvironmentVariableResolver(AgentEnvironmentVariableRefDao refDao,
            SecretCrypto secretCrypto) {
        this.refDao = refDao;
        this.secretCrypto = secretCrypto;
    }

    public Map<String, String> resolve(long tenantId, long agentVersionId) {
        List<AgentEnvironmentVariableSnapshotRow> rows =
                refDao.listResolutionSnapshot(tenantId, agentVersionId);
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }

        Map<String, AgentEnvironmentVariableSnapshotRow> variables = new TreeMap<>();
        Set<String> normalizedNames = new HashSet<>();
        for (AgentEnvironmentVariableSnapshotRow row : rows) {
            if (row == null || !Long.valueOf(tenantId).equals(row.getRefTenantId())
                    || !Long.valueOf(agentVersionId).equals(row.getAgentVersionId())
                    || row.getVariableId() == null
                    || !row.getVariableId().equals(row.getEnvironmentVariableId())
                    || !Long.valueOf(tenantId).equals(row.getVariableTenantId())) {
                throw invalidReference();
            }
            validateName(row.getName());
            if (!normalizedNames.add(row.getName().toUpperCase(Locale.ROOT))) {
                throw new EnvironmentSnapshotResolutionException(
                        "Agent environment variable names must be unique");
            }
            variables.put(row.getName(), row);
        }

        Map<String, String> snapshot = new LinkedHashMap<>();
        for (AgentEnvironmentVariableSnapshotRow variable : variables.values()) {
            try {
                String value = secretCrypto.decrypt(variable.getCredentialRef());
                if (value == null) {
                    throw new EnvironmentSnapshotResolutionException(
                            "Agent environment variable decryption failed");
                }
                snapshot.put(variable.getName(), value);
            } catch (RuntimeException e) {
                throw new EnvironmentSnapshotResolutionException(
                        "Agent environment variable decryption failed");
            }
        }
        return Collections.unmodifiableMap(snapshot);
    }

    private void validateName(String name) {
        if (name == null || name.length() > 128 || !NAME_PATTERN.matcher(name).matches()) {
            throw new EnvironmentSnapshotResolutionException(
                    "Agent environment variable name is invalid");
        }
        if (EnvironmentVariableNamePolicy.isReserved(name)) {
            throw new EnvironmentSnapshotResolutionException(
                    "Agent environment variable name is reserved");
        }
    }

    private EnvironmentSnapshotResolutionException invalidReference() {
        return new EnvironmentSnapshotResolutionException(
                "Agent environment variable reference is unavailable");
    }
}
