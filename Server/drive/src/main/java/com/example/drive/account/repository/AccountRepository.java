package com.example.drive.account.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.drive.account.domain.Account;

public interface AccountRepository extends JpaRepository<Account, UUID> {
}
