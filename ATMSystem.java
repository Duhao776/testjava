import java.util.Scanner;

/**
 * 账户实体类。
 * 该类只负责保存单个账户的基础信息，便于后续切换为文件存储时直接序列化或按行写入。
 */
class Account {
    /**
     * 账户号。
     * 这里使用字符串，便于后续扩展成银行卡号、学号或自定义编号。
     */
    private String accountId;

    /**
     * 账户姓名。
     */
    private String name;

    /**
     * 账户密码。
     */
    private String password;

    /**
     * 账户余额。
     * 这里使用 double 便于课堂实验演示；若用于真实金融系统，应改为 BigDecimal。
     */
    private double balance;

    public Account(String accountId, String name, String password, double balance) {
        this.accountId = accountId;
        this.name = name;
        this.password = password;
        this.balance = balance;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getName() {
        return name;
    }

    public String getPassword() {
        return password;
    }

    public double getBalance() {
        return balance;
    }

    public void setBalance(double balance) {
        this.balance = balance;
    }
}

/**
 * 账户存储接口。
 * 当前实验用数组实现，后续若改成文件存储，只需要新增 FileAccountRepository 实现本接口即可。
 */
interface AccountRepository {
    /**
     * 添加账户。
     *
     * @param account 要添加的账户对象
     * @return 添加成功返回 true，账户号重复返回 false
     */
    boolean addAccount(Account account);

    /**
     * 根据账户号查询账户。
     *
     * @param accountId 账户号
     * @return 找到则返回账户对象，否则返回 null
     */
    Account findById(String accountId);

    /**
     * 获取当前实际存储的账户数量。
     *
     * @return 账户数量
     */
    int size();

    /**
     * 获取全部账户数据。
     * 返回的是长度固定的数组，调用方只遍历前 size() 个元素即可。
     *
     * @return 账户数组
     */
    Account[] findAll();
}

/**
 * 基于数组的账户存储实现。
 * 这是本次实验要求的核心：使用数组保存账户数据。
 * 同时通过扩容逻辑保证后续增加账户时仍具备一定可扩展性。
 */
class ArrayAccountRepository implements AccountRepository {
    /**
     * 用于真正保存账户对象的数组。
     */
    private Account[] accounts;

    /**
     * 当前已经存入数组的有效账户数量。
     */
    private int size;

    public ArrayAccountRepository(int initialCapacity) {
        if (initialCapacity <= 0) {
            initialCapacity = 10;
        }
        this.accounts = new Account[initialCapacity];
        this.size = 0;
    }

    @Override
    public boolean addAccount(Account account) {
        if (account == null || findById(account.getAccountId()) != null) {
            return false;
        }

        ensureCapacity();
        accounts[size] = account;
        size++;
        return true;
    }

    @Override
    public Account findById(String accountId) {
        for (int i = 0; i < size; i++) {
            if (accounts[i].getAccountId().equals(accountId)) {
                return accounts[i];
            }
        }
        return null;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public Account[] findAll() {
        return accounts;
    }

    /**
     * 当数组空间不足时进行扩容。
     * 为了符合“使用数组存储”的要求，这里手动创建新数组并复制元素。
     */
    private void ensureCapacity() {
        if (size < accounts.length) {
            return;
        }

        Account[] newAccounts = new Account[accounts.length * 2];
        for (int i = 0; i < accounts.length; i++) {
            newAccounts[i] = accounts[i];
        }
        accounts = newAccounts;
    }
}

/**
 * ATM 业务类。
 * 该类只负责业务逻辑，不直接关心数据到底来自数组还是文件。
 * 因此后续更换存储方式时，主要替换仓库实现即可。
 */
class ATM {
    /**
     * 账户存储对象。
     * 通过接口引用具体实现，体现可扩展性。
     */
    private AccountRepository repository;

    public ATM(AccountRepository repository) {
        this.repository = repository;
    }

    /**
     * 开户功能。
     * 虽然题目核心是存取转查，但提供开户后程序更完整，也便于测试。
     */
    public boolean createAccount(String accountId, String name, String password, double balance) {
        if (balance < 0) {
            return false;
        }
        Account account = new Account(accountId, name, password, balance);
        return repository.addAccount(account);
    }

    /**
     * 登录校验。
     *
     * @param accountId 账户号
     * @param password 密码
     * @return 校验成功返回账户对象，否则返回 null
     */
    public Account login(String accountId, String password) {
        Account account = repository.findById(accountId);
        if (account == null) {
            return null;
        }

        if (!account.getPassword().equals(password)) {
            return null;
        }

        return account;
    }

    /**
     * 存款。
     *
     * @param account 当前账户
     * @param amount 存款金额
     * @return 操作结果提示
     */
    public String deposit(Account account, double amount) {
        if (account == null) {
            return "账户不存在，无法存款。";
        }
        if (amount <= 0) {
            return "存款金额必须大于 0。";
        }

        account.setBalance(account.getBalance() + amount);
        return "存款成功，当前余额为：" + String.format("%.2f", account.getBalance());
    }

    /**
     * 取款。
     *
     * @param account 当前账户
     * @param amount 取款金额
     * @return 操作结果提示
     */
    public String withdraw(Account account, double amount) {
        if (account == null) {
            return "账户不存在，无法取款。";
        }
        if (amount <= 0) {
            return "取款金额必须大于 0。";
        }
        if (amount > account.getBalance()) {
            return "余额不足，取款失败。";
        }

        account.setBalance(account.getBalance() - amount);
        return "取款成功，当前余额为：" + String.format("%.2f", account.getBalance());
    }

    /**
     * 转账。
     *
     * @param fromAccount 转出账户
     * @param targetId 转入账户号
     * @param amount 转账金额
     * @return 操作结果提示
     */
    public String transfer(Account fromAccount, String targetId, double amount) {
        if (fromAccount == null) {
            return "当前账户不存在，无法转账。";
        }
        if (amount <= 0) {
            return "转账金额必须大于 0。";
        }
        if (fromAccount.getAccountId().equals(targetId)) {
            return "不能给自己转账。";
        }

        Account targetAccount = repository.findById(targetId);
        if (targetAccount == null) {
            return "目标账户不存在，转账失败。";
        }
        if (amount > fromAccount.getBalance()) {
            return "余额不足，转账失败。";
        }

        fromAccount.setBalance(fromAccount.getBalance() - amount);
        targetAccount.setBalance(targetAccount.getBalance() + amount);
        return "转账成功，当前余额为：" + String.format("%.2f", fromAccount.getBalance());
    }

    /**
     * 查询余额。
     *
     * @param account 当前账户
     * @return 余额提示信息
     */
    public String queryBalance(Account account) {
        if (account == null) {
            return "账户不存在，无法查询余额。";
        }
        return "当前余额为：" + String.format("%.2f", account.getBalance());
    }

    /**
     * 显示所有账户信息。
     * 该方法主要用于教师验收或课堂调试。
     */
    public void showAllAccounts() {
        Account[] accounts = repository.findAll();
        int size = repository.size();

        if (size == 0) {
            System.out.println("当前没有任何账户。");
            return;
        }

        System.out.println("===== 所有账户信息 =====");
        for (int i = 0; i < size; i++) {
            System.out.println(
                "账户号：" + accounts[i].getAccountId()
                + "，姓名：" + accounts[i].getName()
                + "，余额：" + String.format("%.2f", accounts[i].getBalance())
            );
        }
    }
}

/**
 * 程序入口类。
 * 提供简单的控制台菜单，演示存款、取款、转账、余额查询等功能。
 */
public class ATMSystem {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        /**
         * 初始化数组存储仓库。
         * 后续如果改成文件存储，只需把这里替换为新的实现类即可。
         */
        AccountRepository repository = new ArrayAccountRepository(5);
        ATM atm = new ATM(repository);

        /**
         * 预置测试账户，方便直接运行程序验证功能。
         */
        atm.createAccount("1001", "张三", "123456", 1000);
        atm.createAccount("1002", "李四", "123456", 2000);
        atm.createAccount("1003", "王五", "123456", 3000);

        while (true) {
            System.out.println("\n===== ATM 系统首页 =====");
            System.out.println("1. 登录账户");
            System.out.println("2. 开户");
            System.out.println("3. 查看所有账户");
            System.out.println("4. 退出系统");
            System.out.print("请输入你的选择：");

            int choice = scanner.nextInt();

            switch (choice) {
                case 1:
                    loginMenu(scanner, atm);
                    break;
                case 2:
                    createAccountMenu(scanner, atm);
                    break;
                case 3:
                    atm.showAllAccounts();
                    break;
                case 4:
                    System.out.println("系统已退出。");
                    scanner.close();
                    return;
                default:
                    System.out.println("输入有误，请重新选择。");
            }
        }
    }

    /**
     * 开户菜单。
     */
    private static void createAccountMenu(Scanner scanner, ATM atm) {
        System.out.print("请输入账户号：");
        String accountId = scanner.next();

        System.out.print("请输入姓名：");
        String name = scanner.next();

        System.out.print("请输入密码：");
        String password = scanner.next();

        System.out.print("请输入初始余额：");
        double balance = scanner.nextDouble();

        boolean success = atm.createAccount(accountId, name, password, balance);
        if (success) {
            System.out.println("开户成功。");
        } else {
            System.out.println("开户失败，可能是账户号重复或初始余额非法。");
        }
    }

    /**
     * 登录菜单。
     */
    private static void loginMenu(Scanner scanner, ATM atm) {
        System.out.print("请输入账户号：");
        String accountId = scanner.next();

        System.out.print("请输入密码：");
        String password = scanner.next();

        Account currentAccount = atm.login(accountId, password);
        if (currentAccount == null) {
            System.out.println("账户号或密码错误，登录失败。");
            return;
        }

        System.out.println("登录成功，欢迎你：" + currentAccount.getName());

        while (true) {
            System.out.println("\n===== ATM 功能菜单 =====");
            System.out.println("1. 存款");
            System.out.println("2. 取款");
            System.out.println("3. 转账");
            System.out.println("4. 余额查询");
            System.out.println("5. 退出登录");
            System.out.print("请输入你的选择：");

            int choice = scanner.nextInt();

            switch (choice) {
                case 1:
                    System.out.print("请输入存款金额：");
                    double depositAmount = scanner.nextDouble();
                    System.out.println(atm.deposit(currentAccount, depositAmount));
                    break;
                case 2:
                    System.out.print("请输入取款金额：");
                    double withdrawAmount = scanner.nextDouble();
                    System.out.println(atm.withdraw(currentAccount, withdrawAmount));
                    break;
                case 3:
                    System.out.print("请输入目标账户号：");
                    String targetId = scanner.next();
                    System.out.print("请输入转账金额：");
                    double transferAmount = scanner.nextDouble();
                    System.out.println(atm.transfer(currentAccount, targetId, transferAmount));
                    break;
                case 4:
                    System.out.println(atm.queryBalance(currentAccount));
                    break;
                case 5:
                    System.out.println("已退出当前账户。");
                    return;
                default:
                    System.out.println("输入有误，请重新选择。");
            }
        }
    }
}
