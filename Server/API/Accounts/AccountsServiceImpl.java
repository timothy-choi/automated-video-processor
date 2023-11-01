package api.auth;

import Java.API.Auth.AccountsService;
import Java.API.Auth.AccountsRepository;
import Java.API.Auth.Accounts;

import java.util.List;



public class AccountsServiceImpl implements AccountsService {
    @Autowired
    private AccountsRepository accountsRepository;

    @Override
    public Accounts findByUsername(String username) {
        return accountsRepository.findByUsername(username);
    }

    @Override
    public Accounts findByAccountId(Long accountId) {
        return accountsRepository.findByAccountId(accountId);
    }

    @Override
    public void deleteAccount(Long accountId) {
        Accounts acct = accountsRepository.findByAccountId(accountId);
        if (acct == null) {
            throw new Exception("Account not found");
        }
        accountsRepository.delete(acct);
    }

    @Override
    public void createAccount(String name, String username, String email) {
        Accounts account = accountsRepository.findByUsername(username);
        if (account != null) {
            throw new Exception("Username already exists");
        }
        Accounts newAcct = new Accounts(name, username, email);
        accountsRepository.save(newAcct);
    }

    @Override
    public void updateUsername(String username, String newUser) {
        Accounts account = accountsRepository.findByUsername(username);
        if (account == null) {
            throw new Exception("Username not found");
        }

        Account newAccount = accountsRepository.findByUsername(newUser);
        if (newAccount != null) {
            throw new Exception("Username already exists");
        }

        account.setUsername(newUser);
        accountsRepository.save(account);
    }

    @Override
    public List<Accounts> findAll() {
        return accountsRepository.findAll();
    }

    @Override
    public void addVideo(String username, Long videoId) {
        Accounts account = accountsRepository.findByUsername(username);
        if (account == null) {
            throw new Exception("Username not found");
        }

        account.addVideo(videoId);
        accountsRepository.save(accounts);
    }

    @Override
    public void addVideoCount(String username, Int change) {
        Accounts account = accountsRepository.findByUsername(username);
        if (account == null) {
            throw new Exception("Username not found");
        }

        account.setNumVideos(change);
        accountsRepository.save(accounts);
    }

}
