package com.railseckill.train.enums;

/**
 * 席别，对应 {@code t_seat_inventory.seat_type} 与 {@code t_order_item.seat_type}。
 *
 * <p>取值约定见 {@code sql/03_rail_inventory.sql} 与 {@code sql/04_rail_order.sql}：
 * 1=商务座 2=一等座 3=二等座。
 */
/*
 * =============================================================================
 *  【为什么要有这个枚举，为什么项目到阶段 5 才第一次出现枚举】
 * =============================================================================
 *
 *   sql/04_rail_order.sql 的「全项目取值约定」那一节明确要求：
 *     **"必须在代码里定义对应的枚举类，禁止裸写数字"**
 *
 *   阶段 0~4 全是查询，不产生也不判定这些取值（查询接口把 seat_type
 *   原样透传给客户端，服务端从不理解它的含义），所以那条约定没有落点。
 *   阶段 5 第一次要**根据席别做判断**（扣哪一行的库存、写哪一行的流水），
 *   于是裸写 `1` / `2` / `3` 的风险第一次真实存在：写错一个数字不会
 *   编译失败、不会抛异常，只会扣错席别的库存。
 *
 * -----------------------------------------------------------------------------
 *  ⚠️ 一个刻意的边界：枚举只在服务端内部用，**对外 DTO 仍然用 1/2/3**
 * -----------------------------------------------------------------------------
 *   dto/SeatAvailability 的 seatType 字段类型是 Integer，不是这个枚举。
 *   这个契约在阶段 4 已经发布了（`GET /api/inventory/seats` 返回 1/2/3），
 *   阶段 5 不去动它。
 *
 *   如果哪天要把 DTO 也改成枚举，那是一次**破坏性的 API 变更**，
 *   应该单独做、并且和客户端一起改，而不是在下单功能里顺手带上。
 *   ——"顺手改契约"是接口漂移最常见的起因。
 * =============================================================================
 */
public enum SeatType {

    /** 商务座。 */
    BUSINESS(1, "商务座"),

    /** 一等座。 */
    FIRST(2, "一等座"),

    /** 二等座。 */
    SECOND(3, "二等座");

    private final int code;

    private final String label;

    SeatType(int code, String label) {
        this.code = code;
        this.label = label;
    }

    /** 数据库里存的值，也用于对外 JSON。 */
    public int code() {
        return code;
    }

    /** 中文名，只用于拼日志和错误信息（比如流水表的 remark）。 */
    public String label() {
        return label;
    }

    /**
     * 由数据库值反查枚举。
     *
     * <p><b>无法识别时抛异常，不返回 null。</b> 取值空间是 DDL 注释约定的三个，
     * 出现第四个说明数据里已经有不属于约定值的东西了。
     * 返回 null 会让这个事实被一个 NPE 掩盖（或者在调用方被当成"普通情况"忽略掉），
     * 抛异常则直接指出是哪一行数据不对。
     *
     * <p>注意：把 unknown 变成一个明确的失败，和 {@link com.railseckill.train.entity.SeatInventory}
     * 里"刻意不写 Math.max(0, remaining)"是同一条原则 —— **让不一致显形，不要静默兜住**。
     */
    public static SeatType of(int code) {
        for (SeatType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的席别取值：" + code + "（约定只有 1/2/3）");
    }
}
