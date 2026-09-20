package com.aliyun.autowonder.category;

import com.aliyun.autowonder.category.dto.CategoryVO;
import com.aliyun.autowonder.category.dto.CreateCategoryRequest;
import com.aliyun.autowonder.category.dto.UpdateCategoryRequest;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看分类")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public Result<List<CategoryVO>> list() {
        return Result.ok(categoryService.list(currentWorkspaceId()));
    }

    @GetMapping("/{id}")
    public Result<CategoryVO> get(@PathVariable("id") Long id) {
        return Result.ok(categoryService.get(id, currentWorkspaceId()));
    }

    @PostMapping
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "创建分类")
    public Result<CategoryVO> create(@RequestBody CreateCategoryRequest req) {
        return Result.ok(categoryService.create(req, currentWorkspaceId(), currentUserId()));
    }

    @PutMapping("/{id}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "更新分类")
    public Result<CategoryVO> update(@PathVariable("id") Long id, @RequestBody JsonNode body) {
        return Result.ok(categoryService.update(id, UpdateCategoryRequest.fromJson(body),
                currentWorkspaceId(), currentUserId()));
    }

    @DeleteMapping("/{id}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "删除分类")
    public Result<Void> delete(@PathVariable("id") Long id) {
        categoryService.delete(id, currentWorkspaceId(), currentUserId());
        return Result.ok(null);
    }

    private long currentUserId() {
        Long uid = AutoWonderContext.get().getUserId();
        if (uid == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return uid;
    }

    private long currentWorkspaceId() {
        Long workspaceId = AutoWonderContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) {
            throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        }
        return workspaceId;
    }
}
