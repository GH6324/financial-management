package com.family.finance.service;

import com.family.finance.domain.account.AccountTemplate;
import com.family.finance.repository.AccountTemplateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AccountTemplateService {

    private final AccountTemplateMapper accountTemplateMapper;

    public List<AccountTemplate> listOrdered() {
        return accountTemplateMapper.listOrdered();
    }

    public Optional<AccountTemplate> find(long id) {
        return accountTemplateMapper.findById(id);
    }
}
