package com.family.finance.service.lens;

/**
 * v1.1.1 · 头寸数据变更事件(填报 / 转账 / 估值刷新 / 账户增改档 / 打标)。
 * 发布方(EntryService / AccountService / AccountValuationService / 打标)与
 * {@link LensQueryService} 经 Spring 事件解耦 —— 消除 v1.1.0 因怕循环依赖
 * 只挂打标失效、其余靠 60s TTL 的设计缺陷(prod 上"每分钟踩一次冷组装"的根因)。
 */
public record LensStaleEvent(long familyId) {}
