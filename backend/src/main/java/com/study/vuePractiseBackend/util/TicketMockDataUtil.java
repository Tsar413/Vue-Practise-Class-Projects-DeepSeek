package com.study.vuePractiseBackend.util;

import com.study.vuePractiseBackend.entity.TicketActivity;
import com.study.vuePractiseBackend.entity.TicketRecord;
import com.study.vuePractiseBackend.entity.TicketUser;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 抢票项目基准数据（全部为虚构演示数据，不含真实学生信息）。
 * 活动时间相对初始化时刻生成，避免固定日期过期。
 */
public final class TicketMockDataUtil {

    private TicketMockDataUtil() {
    }

    public static List<TicketUser> createUsers(Long workspaceId) {
        List<TicketUser> list = new ArrayList<>();

        list.add(createUser(workspaceId, "N0001", "模拟用户001", "13800000001",
                "院系A", "校区A", "USER", 1));
        list.add(createUser(workspaceId, "N0002", "模拟用户002", "13800000002",
                "院系A", "校区A", "USER", 1));
        list.add(createUser(workspaceId, "N0003", "模拟用户003", "13800000003",
                "院系B", "校区A", "USER", 1));
        list.add(createUser(workspaceId, "N0004", "模拟用户004", "13800000004",
                "院系D", "校区B", "USER", 0));
        list.add(createUser(workspaceId, "N0005", "模拟用户005", "13800000005",
                "院系C", "校区B", "ADMIN", 1));

        return list;
    }

    public static List<TicketActivity> createActivities(Long workspaceId, LocalDateTime now) {
        List<TicketActivity> list = new ArrayList<>();

        // 已发布，正在报名。
        list.add(createActivity(workspaceId,
                "教职工电影观影活动",
                "工会组织教职工集体观影，每人限领取一张票。",
                "校区A", "影城A",
                1, 30,
                now.minusDays(1), now.plusDays(2),
                now.plusDays(3), now.plusDays(3).plusHours(2)));

        // 已发布，尚未开始报名。
        list.add(createActivity(workspaceId,
                "周末城市文化参观",
                "参观城市博物馆，集合后统一乘车前往。",
                "校区A", "图书馆门口集合",
                1, 20,
                now.plusDays(1), now.plusDays(3),
                now.plusDays(4), now.plusDays(4).plusHours(4)));

        // 草稿，尚未发布。
        list.add(createActivity(workspaceId,
                "羽毛球活动",
                "提供场地和基础器材，欢迎师生参加。",
                "校区A", "体育馆一楼",
                0, 16,
                now.plusDays(2), now.plusDays(4),
                now.plusDays(5), now.plusDays(5).plusHours(2)));

        // 已关闭，活动已结束。
        list.add(createActivity(workspaceId,
                "工会读书交流会",
                "分享阅读心得，交流教学与生活经验。",
                "校区A", "行政楼二楼报告厅",
                2, 25,
                now.minusDays(7), now.minusDays(5),
                now.minusDays(4), now.minusDays(4).plusHours(2)));

        return list;
    }

    /** 初始抢票记录为空，由业务操作产生。 */
    public static List<TicketRecord> createRecords() {
        return new ArrayList<>();
    }

    private static TicketUser createUser(Long workspaceId, String userNo, String realName,
                                         String phone, String department, String campus,
                                         String role, Integer status) {
        TicketUser user = new TicketUser();
        user.setWorkspaceId(workspaceId);
        user.setUserNo(userNo);
        user.setRealName(realName);
        user.setPhone(phone);
        user.setDepartment(department);
        user.setCampus(campus);
        user.setRole(role);
        user.setStatus(status);
        return user;
    }

    private static TicketActivity createActivity(Long workspaceId, String activityName,
                                                 String description, String campus,
                                                 String location, Integer status, Integer quota,
                                                 LocalDateTime bookingStartTime,
                                                 LocalDateTime bookingEndTime,
                                                 LocalDateTime activityStartTime,
                                                 LocalDateTime activityEndTime) {
        TicketActivity activity = new TicketActivity();
        activity.setWorkspaceId(workspaceId);
        activity.setActivityName(activityName);
        activity.setDescription(description);
        activity.setCoverUrl(null);
        activity.setCampus(campus);
        activity.setLocation(location);
        activity.setStatus(status);
        activity.setQuota(quota);
        activity.setBookedCount(0);
        activity.setBookingStartTime(bookingStartTime);
        activity.setBookingEndTime(bookingEndTime);
        activity.setActivityStartTime(activityStartTime);
        activity.setActivityEndTime(activityEndTime);
        return activity;
    }
}
