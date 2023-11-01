package api.accounts;

import java.util.List;

import API.Auth.Accounts;
import javax.servlet.http.HttpServletResponse;

public interface AccountsService {
    Accounts findByUsername(String username);

    Accounts findByAccountId(Long accountId);

    void deleteAccount(Long accountId);

    void createAccount(String name, String username, String email);

    void updateUsername(String username, HttpServletResponse response);

    List<Accounts> findAll();
}
