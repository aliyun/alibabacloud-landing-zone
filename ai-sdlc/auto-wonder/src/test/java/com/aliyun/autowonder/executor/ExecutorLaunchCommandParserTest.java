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

    @AfterEach void clearLocale() { LocaleContextHolder.resetLocaleContext(); }

    @Test void parsesVersionWorkspaceAndExistingOptionsWithoutLosingQuotedValues() {
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
        assertEquals("0.3.8", config.getRuntimeVersion());
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
