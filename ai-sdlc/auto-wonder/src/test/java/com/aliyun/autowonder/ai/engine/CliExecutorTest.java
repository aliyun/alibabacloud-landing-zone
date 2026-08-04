package com.aliyun.autowonder.ai.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CliExecutorTest {

    @Test
    void parseStreamJsonExtractsAssistantText() {
        String streamOutput = """
                {"type":"assistant","message":{"content":[{"type":"text","text":"Hello world"}]}}
                {"type":"result","result":"Hello world","session_id":"sess-abc"}
                """;
        ByteArrayInputStream in = new ByteArrayInputStream(
                streamOutput.getBytes(StandardCharsets.UTF_8));

        CliExecutor.StreamParseResult parsed = CliExecutor.parseStreamOutput(in);

        assertTrue(parsed.getText().contains("Hello world"));
        assertEquals("sess-abc", parsed.getCliSessionId());
    }

    @Test
    void parseStreamJsonEmitsAssistantTextDeltas() {
        String streamOutput = """
                {"type":"assistant","message":{"content":[{"type":"text","text":"Hello "}]}}
                {"type":"assistant","message":{"content":[{"type":"text","text":"world"}]}}
                """;
        ByteArrayInputStream in = new ByteArrayInputStream(
                streamOutput.getBytes(StandardCharsets.UTF_8));
        List<String> deltas = new ArrayList<>();

        CliExecutor.StreamParseResult parsed = CliExecutor.parseStreamOutput(in, deltas::add);

        assertEquals("Hello world", parsed.getText());
        assertEquals(List.of("Hello ", "world"), deltas);
    }

    @Test
    void parseStreamJsonExtractsJsonBlock() {
        String streamOutput = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Here is the result:\\n```json\\n{\\\"purpose\\\":\\\"test\\\"}\\n```\"}]}}\n" +
                "{\"type\":\"result\",\"result\":\"Here is the result:\\n```json\\n{\\\"purpose\\\":\\\"test\\\"}\\n```\",\"session_id\":\"sess-xyz\"}\n";
        ByteArrayInputStream in = new ByteArrayInputStream(
                streamOutput.getBytes(StandardCharsets.UTF_8));

        CliExecutor.StreamParseResult parsed = CliExecutor.parseStreamOutput(in);

        assertNotNull(parsed.getExtractedJson());
        assertTrue(parsed.getExtractedJson().contains("purpose"));
    }

    @Test
    void parseStreamJsonExtractsFirstJsonObjectFromSurroundingText() {
        String streamOutput = """
                {"type":"assistant","message":{"content":[{"type":"text","text":"好的，下面是 JSON：\\n{\\\"name\\\":\\\"Autowonder 研发工作流\\\",\\\"steps\\\":[{\\\"order\\\":1,\\\"name\\\":\\\"需求满足性分析\\\",\\\"instructionMd\\\":\\\"判断上下文是否足够。\\\"}]}\\n不要写文件。"}]}}
                {"type":"result","result":"done","session_id":"sess-json-text"}
                """;
        ByteArrayInputStream in = new ByteArrayInputStream(
                streamOutput.getBytes(StandardCharsets.UTF_8));

        CliExecutor.StreamParseResult parsed = CliExecutor.parseStreamOutput(in);

        assertNotNull(parsed.getExtractedJson());
        assertTrue(parsed.getExtractedJson().startsWith("{\"name\""));
        assertTrue(parsed.getExtractedJson().contains("需求满足性分析"));
    }

    @Test
    void defaultBuildCommandDoesNotLaunchShell(@TempDir Path workDir) {
        CliExecutor executor = new CliExecutor("/usr/local/bin/claude", 300);
        List<String> cmd = executor.buildCommand("test prompt", null, workDir.toString(),
                "Read,Grep", "You are a scanner.");

        assertEquals("/usr/local/bin/claude", cmd.get(0));
        assertFalse(cmd.contains("/bin/bash"));
        assertTrue(cmd.contains("--output-format"));
        assertTrue(cmd.contains("stream-json"));
        assertTrue(cmd.contains("--verbose"));
        assertTrue(cmd.contains("--allowedTools"));
        assertTrue(cmd.contains("Read,Grep"));
        assertTrue(cmd.contains("-p"));
    }

    @Test
    void shellLaunchModeStillEscapesArguments(@TempDir Path workDir) {
        CliExecutor executor = new CliExecutor("claude", 300, "shell", "/bin/bash");
        List<String> cmd = executor.buildCommand("用户说 'hello'", null, workDir.toString(),
                null, "只输出 'JSON'");

        assertEquals("/bin/bash", cmd.get(0));
        assertEquals("-c", cmd.get(1));
        assertTrue(cmd.get(2).contains("'用户说 '\\''hello'\\'''"));
        assertTrue(cmd.get(2).contains("'只输出 '\\''JSON'\\'''"));
    }

    @Test
    void buildCommandIncludesResumeForMultiTurn(@TempDir Path workDir) {
        CliExecutor executor = new CliExecutor("/usr/local/bin/claude", 300);
        List<String> cmd = executor.buildCommand("follow up", "sess-abc", workDir.toString(),
                null, null);

        assertTrue(cmd.contains("--resume"));
        assertTrue(cmd.contains("sess-abc"));
    }

    @Test
    void directLaunchModePassesArgumentsWithoutShellQuoting(@TempDir Path workDir) {
        CliExecutor executor = new CliExecutor("claude", 300);
        List<String> cmd = executor.buildCommand("用户说 'hello'", null, workDir.toString(),
                null, "只输出 'JSON'");

        assertEquals("claude", cmd.get(0));
        assertTrue(cmd.contains("用户说 'hello'"));
        assertTrue(cmd.contains("只输出 'JSON'"));
    }
}
