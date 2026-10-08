package com.study.vuePractiseBackend.vo;

import com.study.vuePractiseBackend.entity.RepairOrder;
import lombok.Data;

/** 工单详情：工单本体 + 报修人 / 维修师傅姓名。 */
@Data
public class RepairOrderDetailVO {

    private RepairOrder order;

    private String reporterName;

    private String maintainerName;
}
