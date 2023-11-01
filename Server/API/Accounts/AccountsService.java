package api.accounts;

import java.util.List;

import API.Auth.Accounts;

public interface AccountsService {
    Accounts findByUsername(String username);

    Accounts findByAccountId(Long accountId);

    void deleteAccount(Long accountId);

    void createAccount(String name, String username, String email);

    void updateUsername(String username, String newUser);

    void addVideo(String username, Long videoId);

    void addVideoCount(String username, Int change);

    List<Accounts> findAll();
}
