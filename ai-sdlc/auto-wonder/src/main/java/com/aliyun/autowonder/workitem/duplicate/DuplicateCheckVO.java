package com.aliyun.autowonder.workitem.duplicate;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 预检结果：同名结果同步就绪，同意图异步就绪。 */
@Getter
@Setter
public class DuplicateCheckVO {
    private Long checkId;
    private String title;
    private List<DuplicateMatchVO> exactMatches;
    private long exactTotal;
    /** 同名候选超过服务端扫描上限时为 true，界面不得宣称已列出全部。 */
    private boolean exactTruncated;
    /** 全局意图开关当前值。 */
    private boolean intentEnabled;
    /** PENDING / RUNNING / SUCCEEDED / FAILED / CANCELED / SKIPPED；关闭开关时为 null。 */
    private String intentStatus;
    private List<DuplicateMatchVO> intentMatches;
    /** 本次检测未覆盖全部候选时为 true。 */
    private boolean intentPartial;
}
