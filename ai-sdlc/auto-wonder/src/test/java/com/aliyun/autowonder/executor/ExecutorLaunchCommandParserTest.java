package com.aliyun.autowonder.executor;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.result.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class ExecutorLaunchCommandParserTest {
    private static final String COMMAND = "npx -y autowonder@0.3.8 connect --ws-url wss://example.test/ws/executor"
            + " --token 'raw secret' --executor-id 7 --provider qoder --memory-mode platform --max-tasks 5"
            + " --model auto --reasoning-effort medium --context-window 260000 --token-aware-enable";

    @Test void acceptsScopedCommandsAndHistoricalCommandsAgainstScopedBaseline() {
        String scoped = COMMAND.replace("autowonder@0.3.8", "@alicloud/autowonder@0.3.33");
        assertEquals("0.3.33", ExecutorLaunchCommandParser.parse(scoped, COMMAND, 1).getRuntimeVersion());
        assertEquals("0.3.8", ExecutorLaunchCommandParser.parse(COMMAND, scoped, 1).getRuntimeVersion());
        assertNull(ExecutorLaunchCommandParser.parse(scoped.replace("@alicloud/", ""), scoped, 1).getRuntimeVersion());
        for (String spec : List.of("@other/autowonder@0.3.33", "@alicloud/other@0.3.33",
                "autowonder@evil@0.3.33", "@alicloud/autowonder@evil@0.3.33",
                "@alicloud/autowonder@latest", "@alicloud/autowonder@0.3.33-beta.1")) {
            assertThrows(BizException.class, () -> ExecutorLaunchCommandParser.parse(
                    COMMAND.replace("autowonder@0.3.8", spec), scoped, 1));
        }
    }

    @AfterEach void clearLocale() { LocaleContextHolder.resetLocaleContext(); }

    @Test void parsesExplicitVersionChangesAndOtherLaunchOptions() {
        var config = ExecutorLaunchCommandParser.parse(COMMAND.replace("@0.3.8", "@0.3.9")
                .replace("--max-tasks 5", "--max-tasks=3")
                + " --workspace-root '/data/任务 a' --name 'owner'\\''s machine' --debug", COMMAND, 9);
        assertEquals("0.3.9", config.getRuntimeVersion());
        assertEquals("/data/任务 a", config.getWorkspaceRoot());
        assertEquals("owner's machine", config.getDaemonName());
        assertEquals(3, config.getMaxConcurrentDispatches());
        assertEquals("auto", config.getModel());
        assertEquals(9, config.getVersion());
        assertTrue(config.getDebug());
    }

    @Test void onlyAnExplicitVersionChangePinsTheVersion() {
        var pinned = ExecutorLaunchCommandParser.parse(COMMAND, COMMAND, 1);
        var recommended = ExecutorLaunchCommandParser.parse(COMMAND.replace("@0.3.8", "@0.3.30"), COMMAND, 1);
        assertNull(pinned.getRuntimeVersion());
        assertEquals("0.3.30", recommended.getRuntimeVersion());
        assertEquals(pinned.getModel(), recommended.getModel());
    }

    @Test void absentOptionalFlagsExplicitlyClearPreviousOverrides() {
        var config = ExecutorLaunchCommandParser.parse(COMMAND, COMMAND, 1);
        assertEquals("", config.getWorkspaceRoot());
        assertEquals("", config.getDaemonName());
        assertFalse(config.getDebug());
    }

    @Test void acceptsIndentedPosixLineContinuationsInTheCommandEditor() {
        String continuation = " " + '\\' + '\n' + "  ";
        var config = ExecutorLaunchCommandParser.parse(COMMAND.replace(" connect ", " connect" + continuation), COMMAND, 1);
        assertEquals("auto", config.getModel());
        assertNull(config.getRuntimeVersion());
    }

    @Test void rejectsShellExecutionUnknownFlagsAndAmbiguousCommandsWithoutEchoingSecrets() {
        for (String command : List.of(COMMAND + " | tee file", COMMAND + " && touch file",
                COMMAND + "; touch file", COMMAND + " >file", COMMAND + " --unknown secret",
                COMMAND + " --workspace-root $(whoami)", COMMAND + " --workspace-root \"$HOME/test\"",
                COMMAND + " --workspace-root `whoami`", COMMAND + " --workspace-root 'unfinished",
                COMMAND + " --max-tasks 4", COMMAND.replace("--max-tasks 5", "--max-tasks bad"),
                COMMAND.replace("@0.3.8", "@latest"), COMMAND.replace("@0.3.8", "@0.3.9-beta.1"),
                COMMAND.replace("npx -y", "bash -c"), COMMAND.replace(" --model auto", ""))) {
            BizException error = assertThrows(BizException.class,
                    () -> ExecutorLaunchCommandParser.parse(command, COMMAND, 1), command.substring(0, 10));
            assertFalse(error.getMessage().contains("raw secret"));
        }
    }

    @Test void preservesLiteralShellCharactersInSingleQuotedPathAsData() {
        var config = ExecutorLaunchCommandParser.parse(COMMAND + " --workspace-root '/tmp/$literal;name'", COMMAND, 1);
        assertEquals("/tmp/$literal;name", config.getWorkspaceRoot());
    }

    @Test void immutableConnectionAndProxyArgumentsCannotBeChanged() {
        for (String command : List.of(COMMAND.replace("raw secret", "other secret"),
                COMMAND.replace("--executor-id 7", "--executor-id 8"),
                COMMAND.replace("example.test", "other.test"),
                COMMAND.replace("--provider qoder", "--provider qodercn"),
                COMMAND.replace(" --token-aware-enable", ""))) {
            assertThrows(BizException.class, () -> ExecutorLaunchCommandParser.parse(command, COMMAND, 1));
        }
    }

    @Test void errorsRenderInBothLanguagesAndKeepDiagnosticContent() {
        var error = assertThrows(BizException.class,
                () -> ExecutorLaunchCommandParser.parse(COMMAND + " | tee file", COMMAND, 1));
        LocaleContextHolder.setLocale(Locale.US);
        assertTrue(Result.fail(error).getMessage().startsWith("Invalid startup command."));
        LocaleContextHolder.setLocale(Locale.CHINA);
        assertTrue(Result.fail(error).getMessage().startsWith("启动命令格式无效"));
        assertTrue(error.getMessage().startsWith("启动命令格式无效"));
    }
}
