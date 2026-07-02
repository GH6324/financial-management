package com.family.finance.web.todo;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * v0.11.7 · 「待办」页退休 —— 其能力(列出我要填的账户)已被 /entry?mine=true 完全覆盖(且能内联填),
 * 导航「填报」项已接管「·N」未填角标。此处仅保留 302 重定向,照顾老书签 / 历史深链。
 */
@Controller
public class MyTodosController {

    @GetMapping("/my-todos")
    public String myTodos() {
        return "redirect:/entry?mine=true";
    }
}
