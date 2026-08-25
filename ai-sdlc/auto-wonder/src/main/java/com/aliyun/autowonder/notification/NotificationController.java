package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.notification.dto.NotificationVO;
import com.aliyun.autowonder.notification.dto.NotifyPrefVO;
import com.aliyun.autowonder.notification.dto.UpdatePrefRequest;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationDao notificationDao;
    private final NotifyPrefDao prefDao;
    private final NotifyService notifyService;

    public NotificationController(NotificationDao notificationDao, NotifyPrefDao prefDao,
                                   NotifyService notifyService) {
        this.notificationDao = notificationDao;
        this.prefDao = prefDao;
        this.notifyService = notifyService;
    }

    @GetMapping
    public Result<List<NotificationVO>> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        int p = Math.max(page, 1);
        int sz = Math.min(Math.max(size, 1), 100);
        int offset = (p - 1) * sz;
        List<NotificationVO> result = new ArrayList<>();
        for (NotificationDO n : notificationDao.listByRecipient(currentWorkspaceId(), currentUserId(), status, offset, sz)) {
            result.add(toVO(n));
        }
        return Result.ok(result);
    }

    @GetMapping("/unread-count")
    public Result<Integer> unreadCount() {
        return Result.ok(notifyService.unreadCount(currentWorkspaceId(), currentUserId()));
    }

    @PostMapping("/{id}/read")
    public Result<Void> markRead(@PathVariable("id") Long id) {
        notifyService.markRead(id, currentWorkspaceId(), currentUserId());
        return Result.ok(null);
    }

    @PostMapping("/read-all")
    public Result<Void> markAllRead() {
        notifyService.markAllRead(currentWorkspaceId(), currentUserId());
        return Result.ok(null);
    }

    @GetMapping("/prefs")
    public Result<List<NotifyPrefVO>> listPrefs() {
        List<NotifyPrefVO> result = new ArrayList<>();
        for (NotifyPrefDO p : prefDao.listByUser(currentWorkspaceId(), currentUserId())) {
            NotifyPrefVO vo = new NotifyPrefVO();
            vo.setType(p.getType());
            vo.setInApp(p.getInApp() != null && p.getInApp() == 1);
            vo.setDingtalk(p.getDingtalk() != null && p.getDingtalk() == 1);
            result.add(vo);
        }
        return Result.ok(result);
    }

    @PutMapping("/prefs")
    public Result<Void> updatePrefs(@RequestBody UpdatePrefRequest req) {
        if (req.getItems() == null || req.getItems().isEmpty()) {
            return Result.ok(null);
        }
        long tenantId = currentWorkspaceId();
        long userId = currentUserId();
        for (UpdatePrefRequest.PrefItem item : req.getItems()) {
            NotifyPrefDO existing = prefDao.findByUserAndType(tenantId, userId, item.getType());
            if (existing == null) {
                NotifyPrefDO pref = new NotifyPrefDO();
                pref.setTenantId(tenantId);
                pref.setUserId(userId);
                pref.setType(item.getType());
                pref.setInApp(item.isInApp() ? 1 : 0);
                pref.setDingtalk(item.isDingtalk() ? 1 : 0);
                prefDao.insert(pref);
            } else {
                prefDao.update(existing.getId(), item.isInApp() ? 1 : 0, item.isDingtalk() ? 1 : 0);
            }
        }
        return Result.ok(null);
    }

    private NotificationVO toVO(NotificationDO n) {
        NotificationVO vo = new NotificationVO();
        vo.setId(n.getId());
        vo.setType(n.getType());
        vo.setTitle(n.getTitle());
        vo.setContent(n.getContent());
        vo.setLink(n.getLink());
        vo.setRefType(n.getRefType());
        vo.setRefId(n.getRefId());
        vo.setStatus(n.getStatus());
        vo.setGmtCreate(n.getGmtCreate());
        return vo;
    }

    private long currentUserId() {
        Long uid = AutoWonderContext.get().getUserId();
        if (uid == null) { throw new BizException(ErrorCode.UNAUTHORIZED); }
        return uid;
    }

    private long currentWorkspaceId() {
        Long workspaceId = AutoWonderContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) { throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER); }
        return workspaceId;
    }
}
