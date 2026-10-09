package com.study.vuePractiseBackend.util;

import com.study.vuePractiseBackend.entity.RepairDevice;
import com.study.vuePractiseBackend.entity.RepairEvaluation;
import com.study.vuePractiseBackend.entity.RepairOrder;
import com.study.vuePractiseBackend.entity.RepairOrderImage;
import com.study.vuePractiseBackend.entity.RepairProcessRecord;
import com.study.vuePractiseBackend.entity.RepairUser;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 报修项目基准数据（全部为虚构演示数据）。
 * 用户、设备必须先入库并回填主键，工单才能引用真实 ID。
 */
public final class RepairMockDataUtil {

    private RepairMockDataUtil() {
    }

    public static List<RepairUser> createUsers(Long workspaceId, LocalDateTime now) {
        List<RepairUser> list = new ArrayList<>();

        list.add(createUser(workspaceId, "R0001", "模拟用户001", "13800000001",
                "院系A", "REPORTER", 1, now));
        list.add(createUser(workspaceId, "R0002", "模拟用户002", "13800000002",
                "院系B", "REPORTER", 1, now));
        list.add(createUser(workspaceId, "R0003", "模拟用户003", "13800000003",
                "院系C", "REPORTER", 1, now));
        list.add(createUser(workspaceId, "R0004", "模拟用户004", "13800000004",
                "院系A", "REPORTER", 0, now));
        list.add(createUser(workspaceId, "M0001", "维修员示例", "13800000005",
                "行政部门A", "MAINTAINER", 1, now));
        list.add(createUser(workspaceId, "M0002", "维修员示例2", "13800000006",
                "行政部门B", "MAINTAINER", 1, now));
        list.add(createUser(workspaceId, "A0001", "教师示例", "13800000007",
                "行政部门A", "ADMIN", 1, now));
        list.add(createUser(workspaceId, "A0002", "教师示例2", "13800000008",
                "行政部门B", "ADMIN", 1, now));

        return list;
    }

    public static List<RepairDevice> createDevices(Long workspaceId, LocalDateTime now) {
        List<RepairDevice> list = new ArrayList<>();

        list.add(createDevice(workspaceId, "D0001", "教室前后门锁及把手", "门窗木器",
                "校区A", "教学楼A412教室", 1, "前后门锁及门把手", now));
        list.add(createDevice(workspaceId, "D0002", "教室空调", "空调",
                "校区A", "教学楼A416教室", 1, "台式空调", now));
        list.add(createDevice(workspaceId, "D0003", "教室照明灯", "电灯",
                "校区A", "教学楼A515教室", 1, "教室顶部照明灯", now));
        list.add(createDevice(workspaceId, "D0004", "洗手池水龙头", "卫生设施",
                "校区A", "教学楼A五楼卫生间", 1, "洗手池水龙头及连接管路", now));
        list.add(createDevice(workspaceId, "D0005", "教学电脑", "电脑",
                "校区A", "教学楼A516教室讲台", 1, "多媒体教学电脑", now));
        list.add(createDevice(workspaceId, "D0006", "教室投影仪", "投影仪",
                "校区B", "教学楼B201教室", 1, "教室多媒体投影仪", now));
        list.add(createDevice(workspaceId, "D0007", "旧打印机", "打印机",
                "校区A", "办公室A", 0, "已停用，等待资产处理", now));

        return list;
    }

    /** 初始工单全部为状态 1：待分派。 */
    public static List<RepairOrder> createOrders(Long workspaceId,
                                                 List<RepairUser> users,
                                                 List<RepairDevice> devices,
                                                 LocalDateTime now) {
        Map<String, RepairUser> userMap = users.stream()
                .collect(Collectors.toMap(RepairUser::getUserNo, user -> user));
        Map<String, RepairDevice> deviceMap = devices.stream()
                .collect(Collectors.toMap(RepairDevice::getDeviceNo, device -> device));

        List<RepairOrder> list = new ArrayList<>();

        list.add(createOrder(workspaceId, "BX0001", deviceMap.get("D0001"), userMap.get("R0001"),
                "教室门锁及门把手损坏",
                "前门门锁按压后锁舌不弹开，后门门把手脱落，影响教室正常使用，请安排检查维修。",
                now.minusDays(3)));
        list.add(createOrder(workspaceId, "BX0002", deviceMap.get("D0002"), userMap.get("R0001"),
                "教室空调无法正常制冷",
                "空调可以开机，遥控器设置制冷模式后仍持续吹出自然风，已尝试重新开机，问题没有解决。",
                now.minusDays(2)));
        list.add(createOrder(workspaceId, "BX0003", deviceMap.get("D0003"), userMap.get("R0002"),
                "教室照明灯闪烁",
                "教室靠窗一排照明灯持续闪烁，其中一盏完全不亮，影响上课和学生阅读。",
                now.minusDays(1)));
        list.add(createOrder(workspaceId, "BX0004", deviceMap.get("D0004"), userMap.get("R0002"),
                "卫生间水龙头漏水",
                "水龙头关闭后仍持续滴水，洗手池下方连接处有渗水，地面容易积水，请检查阀芯和连接管路。",
                now.minusHours(8)));
        list.add(createOrder(workspaceId, "BX0005", deviceMap.get("D0005"), userMap.get("R0001"),
                "教学电脑无法启动",
                "按下电源键后主机指示灯亮，但显示器没有画面，已检查显示器电源及连接线，仍无法正常使用。",
                now.minusHours(4)));
        list.add(createOrder(workspaceId, "BX0006", deviceMap.get("D0006"), userMap.get("R0003"),
                "投影仪显示无信号",
                "投影仪可以开机，但连接教学电脑后显示无信号，切换输入源并重新连接后问题仍存在。",
                now.minusHours(1)));

        return list;
    }

    /** 为每张初始工单生成一条 CREATE 处理记录，工单必须已回填主键。 */
    public static List<RepairProcessRecord> createProcessRecords(List<RepairOrder> orders) {
        List<RepairProcessRecord> list = new ArrayList<>();

        for (RepairOrder order : orders) {
            if (order.getId() == null) {
                throw new IllegalArgumentException("请先保存工单并回填id：" + order.getOrderNo());
            }
            RepairProcessRecord record = new RepairProcessRecord();
            record.setWorkspaceId(order.getWorkspaceId());
            record.setOrderId(order.getId());
            record.setOperatorId(order.getReporterId());
            record.setAction("CREATE");
            record.setFromStatus(null);
            record.setToStatus(1);
            record.setTargetUserId(null);
            record.setContent("报修人提交工单：" + order.getDescription());
            record.setCreateTime(order.getCreateTime());
            record.setUpdateTime(order.getCreateTime());
            list.add(record);
        }
        return list;
    }

    /** 初始不预置图片。 */
    public static List<RepairOrderImage> createImages() {
        return new ArrayList<>();
    }

    /** 初始工单尚未完成，不生成评价。 */
    public static List<RepairEvaluation> createEvaluations() {
        return new ArrayList<>();
    }

    private static RepairUser createUser(Long workspaceId, String userNo, String realName,
                                         String phone, String department, String role,
                                         Integer status, LocalDateTime now) {
        RepairUser user = new RepairUser();
        user.setWorkspaceId(workspaceId);
        user.setUserNo(userNo);
        user.setRealName(realName);
        user.setPhone(phone);
        user.setDepartment(department);
        user.setRole(role);
        user.setStatus(status);
        user.setCreateTime(now.minusDays(30));
        user.setUpdateTime(now.minusDays(30));
        return user;
    }

    private static RepairDevice createDevice(Long workspaceId, String deviceNo, String deviceName,
                                             String deviceType, String campus, String location,
                                             Integer status, String remark, LocalDateTime now) {
        RepairDevice device = new RepairDevice();
        device.setWorkspaceId(workspaceId);
        device.setDeviceNo(deviceNo);
        device.setDeviceName(deviceName);
        device.setDeviceType(deviceType);
        device.setCampus(campus);
        device.setLocation(location);
        device.setStatus(status);
        device.setRemark(remark);
        device.setCreateTime(now.minusDays(30));
        device.setUpdateTime(now.minusDays(30));
        return device;
    }

    private static RepairOrder createOrder(Long workspaceId, String orderNo, RepairDevice device,
                                           RepairUser reporter, String title, String description,
                                           LocalDateTime createTime) {
        if (device == null || device.getId() == null
                || reporter == null || reporter.getId() == null) {
            throw new IllegalArgumentException("请先保存对应用户和设备并回填id：" + orderNo);
        }
        if (!Objects.equals(workspaceId, device.getWorkspaceId())
                || !Objects.equals(workspaceId, reporter.getWorkspaceId())) {
            throw new IllegalArgumentException("用户和设备必须属于当前工作空间");
        }

        RepairOrder order = new RepairOrder();
        order.setWorkspaceId(workspaceId);
        order.setOrderNo(orderNo);
        order.setDeviceId(device.getId());
        // 保存报修时的设备信息快照
        order.setDeviceName(device.getDeviceName());
        order.setDeviceType(device.getDeviceType());
        order.setLocation(device.getLocation());
        order.setCampus(device.getCampus());
        order.setTitle(title);
        order.setDescription(description);
        order.setReporterId(reporter.getId());
        order.setContactPhone(reporter.getPhone());
        order.setMaintainerId(null);
        order.setStatus(1);
        order.setRepairResult(null);
        order.setCompletedTime(null);
        order.setCancelTime(null);
        order.setCreateTime(createTime);
        order.setUpdateTime(createTime);
        return order;
    }
}
