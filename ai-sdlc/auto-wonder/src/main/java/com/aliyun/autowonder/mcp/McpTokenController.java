package com.aliyun.autowonder.mcp;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.mcp.dto.CreateMcpTokenRequest;
import com.aliyun.autowonder.mcp.dto.IssuedMcpTokenVO;
import com.aliyun.autowonder.mcp.dto.McpAccessTokenVO;
import com.aliyun.autowonder.mcp.dto.McpToolVO;
import com.aliyun.autowonder.mcp.dto.PlatformSkillVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/mcp/tokens")
public class McpTokenController {
    private final McpAccessTokenService tokenService;
    private final McpToolService toolService;
    private final PlatformSkillCatalog platformSkillCatalog;

    public McpTokenController(McpAccessTokenService tokenService, McpToolService toolService,
                              PlatformSkillCatalog platformSkillCatalog) {
        this.tokenService = tokenService;
        this.toolService = toolService;
        this.platformSkillCatalog = platformSkillCatalog;
    }

    @PostMapping
    public Result<IssuedMcpTokenVO> issue(@RequestBody(required = false) CreateMcpTokenRequest req) {
        return Result.ok(tokenService.issue(
                req == null ? null : req.getName(), currentUserId()));
    }

    @GetMapping
    public Result<List<McpAccessTokenVO>> list() {
        return Result.ok(tokenService.list(currentUserId()));
    }

    @GetMapping("/tools")
    public Result<List<McpToolVO>> tools() {
        currentUserId();
        return Result.ok(toolService.listTools());
    }

    @GetMapping("/platform-skills")
    public Result<List<PlatformSkillVO>> platformSkills() {
        currentUserId();
        return Result.ok(platformSkillCatalog.list());
    }

    @DeleteMapping("/{id}")
    public Result<Void> revoke(@PathVariable("id") Long id) {
        tokenService.revoke(id, currentUserId());
        return Result.ok(null);
    }

    private long currentUserId() {
        Long uid = AutoWonderContext.get().getUserId();
        if (uid == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return uid;
    }
}
