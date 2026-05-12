import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

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

    /**
     * 保存账户变更。
     * 默认实现用于内存存储：账户对象已经在内存中被修改，因此不需要额外动作。
     * 文件、数据库等持久化实现可以覆盖该方法，把最新账户数据写入外部存储。
     *
     * @return 保存成功返回 true，保存失败返回 false
     */
    default boolean saveChanges() {
        return true;
    }
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
    protected Account[] accounts;

    /**
     * 当前已经存入数组的有效账户数量。
     */
    protected int size;

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
    protected void ensureCapacity() {
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
 * 基于文件的账户存储实现。
 * 该类复用 AccountRepository 接口和数组仓库的基础能力，只负责把账户数组加载到文件、保存到文件。
 */
class FileAccountRepository extends ArrayAccountRepository {
    /**
     * 账户数据文件路径。
     * 当前使用项目根目录下的 accounts.txt，便于课堂演示和直接查看。
     */
    private Path filePath;

    public FileAccountRepository(String fileName, int initialCapacity) {
        super(initialCapacity);
        this.filePath = Path.of(fileName);
        loadFromFile();
    }

    @Override
    public boolean addAccount(Account account) {
        if (account == null || findById(account.getAccountId()) != null) {
            return false;
        }

        ensureCapacity();
        accounts[size] = account;
        size++;

        if (saveChanges()) {
            return true;
        }

        /*
         * 如果文件保存失败，需要撤回本次开户在内存中的修改。
         * 这样可以避免界面提示开户失败，但内存里实际已经存在该账户的矛盾状态。
         */
        size--;
        accounts[size] = null;
        return false;
    }

    @Override
    public boolean saveChanges() {
        try {
            Path parentPath = filePath.getParent();
            if (parentPath != null) {
                Files.createDirectories(parentPath);
            }

            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < size; i++) {
                builder.append(toFileLine(accounts[i])).append(System.lineSeparator());
            }

            /*
             * 先写入临时文件，再替换正式文件。
             * 这样可以降低写入中途失败导致正式账户文件损坏的风险。
             */
            Path tempPath = filePath.resolveSibling(filePath.getFileName() + ".tmp");
            Files.writeString(tempPath, builder.toString(), StandardCharsets.UTF_8);
            Files.move(tempPath, filePath, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException exception) {
            System.out.println("账户数据保存失败：" + exception.getMessage());
            return false;
        }
    }

    /**
     * 从文件加载账户数据。
     * 文件不存在时表示首次运行程序，直接使用空账户数组即可。
     */
    private void loadFromFile() {
        if (!Files.exists(filePath)) {
            return;
        }

        try {
            for (String line : Files.readAllLines(filePath, StandardCharsets.UTF_8)) {
                Account account = fromFileLine(line);
                if (account == null || findById(account.getAccountId()) != null) {
                    continue;
                }

                ensureCapacity();
                accounts[size] = account;
                size++;
            }
        } catch (IOException exception) {
            System.out.println("账户数据加载失败：" + exception.getMessage());
        }
    }

    /**
     * 将账户对象转换为一行文本。
     * 字符串字段使用 Base64 编码，避免姓名、密码中出现分隔符导致解析错误。
     */
    private String toFileLine(Account account) {
        return encode(account.getAccountId())
            + "\t" + encode(account.getName())
            + "\t" + encode(account.getPassword())
            + "\t" + account.getBalance();
    }

    /**
     * 将文件中的一行文本还原为账户对象。
     * 遇到空行或格式错误的数据时返回 null，保证单行坏数据不会影响整个程序启动。
     */
    private Account fromFileLine(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }

        String[] parts = line.split("\t");
        if (parts.length != 4) {
            return null;
        }

        try {
            return new Account(
                decode(parts[0]),
                decode(parts[1]),
                decode(parts[2]),
                Double.parseDouble(parts[3])
            );
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /**
     * 对普通字符串进行 Base64 编码，保证文件中的字段分隔稳定。
     */
    private String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 对 Base64 字符串进行解码，恢复原始账户字段。
     */
    private String decode(String value) {
        byte[] bytes = Base64.getDecoder().decode(value);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}

/**
 * 基于 SQLite 数据库的账户存储实现。
 * 该类继续实现 AccountRepository 接口，ATM 业务层无需关心底层是文件还是数据库。
 */
class SqliteAccountRepository extends ArrayAccountRepository {
    /**
     * 硬编码 SQLite JDBC 驱动 jar 的本机绝对位置。
     * 运行程序时即使没有手动添加 classpath，也会优先从这里加载 sqlite-jdbc 驱动。
     */
    private static final Path SQLITE_DRIVER_JAR_PATH = Path.of(
        "./lib/sqlite-jdbc-3.53.0.0.jar"
    );

    /**
     * 标记驱动是否已经注册到 DriverManager，避免多次创建仓库时重复注册同一个驱动。
     */
    private static boolean sqliteDriverLoaded = false;

    /**
     * SQLite 数据库连接地址。
     * jdbc:sqlite: 后面跟数据库文件路径，SQLite 会把数据保存在该文件中。
     */
    private String databaseUrl;

    public SqliteAccountRepository(String databaseFileName, int initialCapacity) {
        super(initialCapacity);
        ensureSqliteDriverLoaded();
        this.databaseUrl = "jdbc:sqlite:" + databaseFileName;
        initializeDatabase();
        loadFromDatabase();
    }

    @Override
    public boolean addAccount(Account account) {
        if (account == null || findById(account.getAccountId()) != null) {
            return false;
        }

        ensureCapacity();
        accounts[size] = account;
        size++;

        if (saveChanges()) {
            return true;
        }

        /*
         * 数据库保存失败时撤回内存数组中的新增账户。
         * 这样可以保证内存状态和数据库状态保持一致。
         */
        size--;
        accounts[size] = null;
        return false;
    }

    @Override
    public boolean saveChanges() {
        /*
         * 这里采用“先删除再批量插入”的方式保存完整账户快照。
         * 对当前小型课堂程序来说实现简单，并且能统一处理开户、存款、取款、转账后的持久化。
         */
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);

            try (
                Statement deleteStatement = connection.createStatement();
                PreparedStatement insertStatement = connection.prepareStatement(
                    "INSERT INTO accounts (account_id, name, password, balance) VALUES (?, ?, ?, ?)"
                )
            ) {
                deleteStatement.executeUpdate("DELETE FROM accounts");

                for (int i = 0; i < size; i++) {
                    insertStatement.setString(1, accounts[i].getAccountId());
                    insertStatement.setString(2, accounts[i].getName());
                    insertStatement.setString(3, accounts[i].getPassword());
                    insertStatement.setDouble(4, accounts[i].getBalance());
                    insertStatement.addBatch();
                }

                insertStatement.executeBatch();
                connection.commit();
                return true;
            } catch (SQLException exception) {
                /*
                 * 事务中任一步失败都回滚，避免数据库只保存了部分账户数据。
                 * 回滚本身也可能失败，因此单独捕获，保留原始保存失败信息。
                 */
                try {
                    connection.rollback();
                } catch (SQLException rollbackException) {
                    System.out.println("账户数据回滚失败：" + rollbackException.getMessage());
                }
                System.out.println("账户数据保存失败：" + exception.getMessage());
                return false;
            }
        } catch (SQLException exception) {
            System.out.println("账户数据库连接失败：" + exception.getMessage());
            return false;
        }
    }

    /**
     * 初始化数据库表结构。
     * 如果表已经存在，CREATE TABLE IF NOT EXISTS 不会破坏已有账户数据。
     */
    private void initializeDatabase() {
        try (
            Connection connection = openConnection();
            Statement statement = connection.createStatement()
        ) {
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS accounts ("
                    + "account_id TEXT PRIMARY KEY,"
                    + "name TEXT NOT NULL,"
                    + "password TEXT NOT NULL,"
                    + "balance REAL NOT NULL"
                    + ")"
            );
        } catch (SQLException exception) {
            throw new IllegalStateException("SQLite 数据库初始化失败：" + exception.getMessage(), exception);
        }
    }

    /**
     * 从 SQLite 数据库加载账户数据到内存数组。
     * 后续查询仍然走 AccountRepository 接口，业务层不需要改变调用方式。
     */
    private void loadFromDatabase() {
        try (
            Connection connection = openConnection();
            PreparedStatement statement = connection.prepareStatement(
                "SELECT account_id, name, password, balance FROM accounts ORDER BY account_id"
            );
            ResultSet resultSet = statement.executeQuery()
        ) {
            while (resultSet.next()) {
                Account account = new Account(
                    resultSet.getString("account_id"),
                    resultSet.getString("name"),
                    resultSet.getString("password"),
                    resultSet.getDouble("balance")
                );

                ensureCapacity();
                accounts[size] = account;
                size++;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("SQLite 账户数据加载失败：" + exception.getMessage(), exception);
        }
    }

    /**
     * 打开 SQLite 数据库连接。
     * 驱动已经在仓库构造阶段从硬编码 jar 位置加载，因此这里可以直接通过 DriverManager 建立连接。
     */
    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(databaseUrl);
    }

    /**
     * 从硬编码 jar 路径加载 SQLite JDBC 驱动。
     * 这里使用反射创建驱动实例，避免源码直接 import org.sqlite.JDBC 导致 javac 编译时依赖第三方 jar。
     */
    private static synchronized void ensureSqliteDriverLoaded() {
        if (sqliteDriverLoaded) {
            return;
        }

        Path driverPath = SQLITE_DRIVER_JAR_PATH.toAbsolutePath().normalize();
        if (!Files.exists(driverPath)) {
            throw new IllegalStateException("找不到 SQLite JDBC 驱动文件：" + driverPath);
        }

        try {
            URL driverUrl = driverPath.toUri().toURL();

            /*
             * 使用独立类加载器加载 jar 内的 org.sqlite.JDBC。
             * 父加载器使用当前类加载器，确保 java.sql 等标准类仍由 JDK 提供。
             */
            URLClassLoader driverClassLoader = new URLClassLoader(
                new URL[] {driverUrl},
                SqliteAccountRepository.class.getClassLoader()
            );
            Class<?> driverClass = Class.forName("org.sqlite.JDBC", true, driverClassLoader);
            Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();

            /*
             * DriverManager 会按调用方类加载器过滤驱动。
             * 包一层当前源码中的 Driver 代理后，普通 java ATMSystem 启动也能拿到该驱动。
             */
            DriverManager.registerDriver(new HardcodedJdbcDriver(driver));
            sqliteDriverLoaded = true;
        } catch (Exception exception) {
            throw new IllegalStateException("SQLite JDBC 驱动加载失败：" + exception.getMessage(), exception);
        }
    }
}

/**
 * JDBC 驱动代理。
 * 该类由应用自己的类加载器加载，用来把硬编码 jar 中的 SQLite Driver 安全注册给 DriverManager。
 */
class HardcodedJdbcDriver implements Driver {
    /**
     * 实际来自 sqlite-jdbc jar 的驱动对象。
     */
    private final Driver driver;

    public HardcodedJdbcDriver(Driver driver) {
        this.driver = driver;
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        return driver.connect(url, info);
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        return driver.acceptsURL(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return driver.getPropertyInfo(url, info);
    }

    @Override
    public int getMajorVersion() {
        return driver.getMajorVersion();
    }

    @Override
    public int getMinorVersion() {
        return driver.getMinorVersion();
    }

    @Override
    public boolean jdbcCompliant() {
        return driver.jdbcCompliant();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return driver.getParentLogger();
    }
}

/**
 * 账户仓库工厂。
 * 通过启动参数选择存储实现，避免在 ATM 业务类中硬编码文件或数据库细节。
 */
class AccountRepositoryFactory {
    /**
     * 创建账户仓库。
     * 参数为 sqlite 时使用 SQLite 数据库存储；参数为 file 或不传参数时使用文件存储。
     */
    public static AccountRepository create(String[] args) {
        String storageType = "file";
        if (args != null && args.length > 0) {
            storageType = args[0].trim().toLowerCase();
        }

        if ("sqlite".equals(storageType) || "db".equals(storageType)) {
            System.out.println("当前存储方式：SQLite 数据库（atm.db）");
            return new SqliteAccountRepository("atm.db", 5);
        }

        if (!"file".equals(storageType)) {
            System.out.println("未知存储方式：" + storageType + "，已自动使用文件存储。");
        }

        System.out.println("当前存储方式：文件（accounts.txt）");
        return new FileAccountRepository("accounts.txt", 5);
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
    public synchronized boolean createAccount(String accountId, String name, String password, double balance) {
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
    public synchronized Account login(String accountId, String password) {
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
    public synchronized String deposit(Account account, double amount) {
        if (account == null) {
            return "账户不存在，无法存款。";
        }
        if (amount <= 0) {
            return "存款金额必须大于 0。";
        }

        double oldBalance = account.getBalance();
        account.setBalance(oldBalance + amount);
        if (!repository.saveChanges()) {
            account.setBalance(oldBalance);
            return "存款失败，账户数据保存失败。";
        }
        return "存款成功，当前余额为：" + String.format("%.2f", account.getBalance());
    }

    /**
     * 取款。
     *
     * @param account 当前账户
     * @param amount 取款金额
     * @return 操作结果提示
     */
    public synchronized String withdraw(Account account, double amount) {
        if (account == null) {
            return "账户不存在，无法取款。";
        }
        if (amount <= 0) {
            return "取款金额必须大于 0。";
        }
        if (amount > account.getBalance()) {
            return "余额不足，取款失败。";
        }

        double oldBalance = account.getBalance();
        account.setBalance(oldBalance - amount);
        if (!repository.saveChanges()) {
            account.setBalance(oldBalance);
            return "取款失败，账户数据保存失败。";
        }
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
    public synchronized String transfer(Account fromAccount, String targetId, double amount) {
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

        double oldFromBalance = fromAccount.getBalance();
        double oldTargetBalance = targetAccount.getBalance();

        fromAccount.setBalance(oldFromBalance - amount);
        targetAccount.setBalance(oldTargetBalance + amount);
        if (!repository.saveChanges()) {
            fromAccount.setBalance(oldFromBalance);
            targetAccount.setBalance(oldTargetBalance);
            return "转账失败，账户数据保存失败。";
        }
        return "转账成功，当前余额为：" + String.format("%.2f", fromAccount.getBalance());
    }

    /**
     * 查询余额。
     *
     * @param account 当前账户
     * @return 余额提示信息
     */
    public synchronized String queryBalance(Account account) {
        if (account == null) {
            return "账户不存在，无法查询余额。";
        }
        return "当前余额为：" + String.format("%.2f", account.getBalance());
    }

    /**
     * 根据账户号获取账户对象。
     * 浏览器访问模式下，登录状态只在 Cookie 中保存账户号，每次请求都重新从仓库取最新账户。
     *
     * @param accountId 账户号
     * @return 找到则返回账户对象，否则返回 null
     */
    public synchronized Account findAccount(String accountId) {
        return repository.findById(accountId);
    }

    /**
     * 获取当前账户数量。
     * 浏览器页面展示账户列表时只遍历 findAll() 返回数组中的有效部分。
     *
     * @return 当前有效账户数量
     */
    public synchronized int getAccountCount() {
        return repository.size();
    }

    /**
     * 获取账户数组。
     * 这里沿用现有仓库接口，减少为了浏览器页面而对存储层做额外改动。
     *
     * @return 账户数组
     */
    public synchronized Account[] getAllAccounts() {
        return repository.findAll();
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
 * 浏览器请求对象。
 * 由于本项目要求使用 socket 编程，这里手动解析 HTTP 请求行、请求头、Cookie 和表单参数。
 */
class HttpRequest {
    private String method;
    private String path;
    private Map<String, String> queryParameters;
    private Map<String, String> formParameters;
    private Map<String, String> headers;
    private Map<String, String> cookies;

    private HttpRequest(String method, String path) {
        this.method = method;
        this.path = path;
        this.queryParameters = new HashMap<>();
        this.formParameters = new HashMap<>();
        this.headers = new HashMap<>();
        this.cookies = new HashMap<>();
    }

    /**
     * 从 socket 输入流中解析一个 HTTP 请求。
     * 这里按字节读取请求头，再按 Content-Length 读取请求体，避免中文表单内容因为字节数和字符数不一致而读取错误。
     *
     * @param input socket 输入流
     * @return 解析后的请求对象；如果浏览器断开连接则返回 null
     * @throws IOException 网络读取异常
     */
    public static HttpRequest parse(InputStream input) throws IOException {
        byte[] delimiter = new byte[] {13, 10, 13, 10};
        ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream();
        int matched = 0;

        while (true) {
            int currentByte = input.read();
            if (currentByte == -1) {
                return null;
            }

            headerBuffer.write(currentByte);
            if (currentByte == delimiter[matched]) {
                matched++;
                if (matched == delimiter.length) {
                    break;
                }
            } else {
                matched = currentByte == delimiter[0] ? 1 : 0;
            }

            if (headerBuffer.size() > 32768) {
                throw new IOException("HTTP 请求头过大。");
            }
        }

        byte[] headerBytes = headerBuffer.toByteArray();
        String headerText = new String(headerBytes, 0, headerBytes.length - delimiter.length, StandardCharsets.ISO_8859_1);
        String[] lines = headerText.split("\r\n");
        if (lines.length == 0 || lines[0].trim().isEmpty()) {
            throw new IOException("HTTP 请求行为空。");
        }

        String[] requestLineParts = lines[0].split(" ");
        if (requestLineParts.length < 2) {
            throw new IOException("HTTP 请求行格式错误。");
        }

        String requestTarget = requestLineParts[1];
        String path = requestTarget;
        String queryText = "";
        int queryIndex = requestTarget.indexOf('?');
        if (queryIndex >= 0) {
            path = requestTarget.substring(0, queryIndex);
            queryText = requestTarget.substring(queryIndex + 1);
        }

        HttpRequest request = new HttpRequest(requestLineParts[0], path);
        request.queryParameters.putAll(parseParameters(queryText));

        for (int i = 1; i < lines.length; i++) {
            int colonIndex = lines[i].indexOf(':');
            if (colonIndex <= 0) {
                continue;
            }

            String headerName = lines[i].substring(0, colonIndex).trim().toLowerCase();
            String headerValue = lines[i].substring(colonIndex + 1).trim();
            request.headers.put(headerName, headerValue);
        }

        request.cookies.putAll(parseCookies(request.headers.get("cookie")));

        int contentLength = parseContentLength(request.headers.get("content-length"));
        if (contentLength > 0) {
            byte[] bodyBytes = input.readNBytes(contentLength);
            String bodyText = new String(bodyBytes, StandardCharsets.UTF_8);
            request.formParameters.putAll(parseParameters(bodyText));
        }

        return request;
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    /**
     * 获取请求参数。
     * 表单参数优先于 URL 参数，便于 POST 表单覆盖同名查询参数。
     *
     * @param name 参数名
     * @return 参数值；不存在时返回空字符串
     */
    public String parameter(String name) {
        if (formParameters.containsKey(name)) {
            return formParameters.get(name);
        }
        return queryParameters.getOrDefault(name, "");
    }

    public String cookie(String name) {
        return cookies.getOrDefault(name, "");
    }

    /**
     * 解析 application/x-www-form-urlencoded 格式参数。
     * 浏览器表单默认会把空格编码成加号，URLDecoder 会自动恢复为空格。
     */
    private static Map<String, String> parseParameters(String text) {
        Map<String, String> parameters = new HashMap<>();
        if (text == null || text.isEmpty()) {
            return parameters;
        }

        String[] pairs = text.split("&");
        for (String pair : pairs) {
            if (pair.isEmpty()) {
                continue;
            }

            int equalsIndex = pair.indexOf('=');
            String rawName = equalsIndex >= 0 ? pair.substring(0, equalsIndex) : pair;
            String rawValue = equalsIndex >= 0 ? pair.substring(equalsIndex + 1) : "";
            parameters.put(
                URLDecoder.decode(rawName, StandardCharsets.UTF_8),
                URLDecoder.decode(rawValue, StandardCharsets.UTF_8)
            );
        }

        return parameters;
    }

    /**
     * 解析 Cookie 请求头。
     * 当前只需要 SID 登录标识，但保留通用解析逻辑，便于以后扩展更多 Cookie。
     */
    private static Map<String, String> parseCookies(String cookieHeader) {
        Map<String, String> cookies = new HashMap<>();
        if (cookieHeader == null || cookieHeader.isEmpty()) {
            return cookies;
        }

        String[] pairs = cookieHeader.split(";");
        for (String pair : pairs) {
            int equalsIndex = pair.indexOf('=');
            if (equalsIndex <= 0) {
                continue;
            }

            String name = pair.substring(0, equalsIndex).trim();
            String value = pair.substring(equalsIndex + 1).trim();
            cookies.put(name, value);
        }

        return cookies;
    }

    /**
     * 安全解析 Content-Length。
     * 如果浏览器发送了非法长度，直接按 0 处理，后续业务会因为参数缺失而给出错误提示。
     */
    private static int parseContentLength(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }

        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }
}

/**
 * 基于 ServerSocket 的 ATM 浏览器访问服务。
 * 该类只负责 HTTP 交互、页面渲染和异常兜底，账户业务仍然交给 ATM 类处理。
 */
class ATMHttpServer {
    private static final String SESSION_COOKIE_NAME = "SID";

    private ATM atm;
    private int port;
    private Map<String, String> sessions;

    public ATMHttpServer(ATM atm, int port) {
        this.atm = atm;
        this.port = port;
        this.sessions = new ConcurrentHashMap<>();
    }

    /**
     * 启动 socket 服务。
     * 每个浏览器连接使用一个独立线程处理，避免单个请求阻塞后续访问。
     */
    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("ATM 浏览器服务已启动：http://localhost:" + port + "/");
            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(() -> handleClient(clientSocket)).start();
            }
        } catch (IOException exception) {
            System.out.println("ATM 浏览器服务启动失败：" + exception.getMessage());
        }
    }

    /**
     * 处理单个浏览器连接。
     * 所有异常都会被捕获并转换成 HTTP 错误页，避免服务线程直接崩溃。
     */
    private void handleClient(Socket clientSocket) {
        try (Socket socket = clientSocket) {
            HttpRequest request = HttpRequest.parse(socket.getInputStream());
            if (request == null) {
                return;
            }

            route(request, socket.getOutputStream());
        } catch (Exception exception) {
            try {
                sendHtml(clientSocket.getOutputStream(), 500, renderLayout("系统异常：" + escapeHtml(exception.getMessage()), null, null), null);
            } catch (IOException ignoredException) {
                System.out.println("发送错误响应失败：" + ignoredException.getMessage());
            }
            System.out.println("处理浏览器请求失败：" + exception.getMessage());
        }
    }

    /**
     * 根据请求路径分发业务。
     * GET 用于展示页面，POST 用于执行会修改状态的业务操作。
     */
    private void route(HttpRequest request, OutputStream output) throws IOException {
        if ("GET".equals(request.getMethod()) && "/".equals(request.getPath())) {
            sendHome(request, output);
            return;
        }

        if ("GET".equals(request.getMethod()) && "/favicon.ico".equals(request.getPath())) {
            sendNoContent(output);
            return;
        }

        if (!"POST".equals(request.getMethod())) {
            sendHtml(output, 404, renderLayout("页面不存在。", null, null), null);
            return;
        }

        switch (request.getPath()) {
            case "/login":
                handleLogin(request, output);
                break;
            case "/create":
                handleCreateAccount(request, output);
                break;
            case "/deposit":
                handleDeposit(request, output);
                break;
            case "/withdraw":
                handleWithdraw(request, output);
                break;
            case "/transfer":
                handleTransfer(request, output);
                break;
            case "/logout":
                handleLogout(request, output);
                break;
            default:
                sendHtml(output, 404, renderLayout("页面不存在。", null, null), null);
                break;
        }
    }

    /**
     * 展示首页。
     * 未登录时显示登录和开户表单，已登录时显示当前账户操作区和账户列表。
     */
    private void sendHome(HttpRequest request, OutputStream output) throws IOException {
        Account currentAccount = getCurrentAccount(request);
        String message = request.parameter("message");
        sendHtml(output, 200, renderLayout(renderHomeContent(currentAccount), message, currentAccount), null);
    }

    /**
     * 处理登录表单。
     * 登录成功后生成随机会话编号，并通过 Cookie 返回给浏览器。
     */
    private void handleLogin(HttpRequest request, OutputStream output) throws IOException {
        String accountId = request.parameter("accountId").trim();
        String password = request.parameter("password");
        Account account = atm.login(accountId, password);

        if (account == null) {
            redirect(output, "/?message=" + encodeUrl("账户号或密码错误，登录失败。"), null);
            return;
        }

        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, account.getAccountId());
        String cookie = SESSION_COOKIE_NAME + "=" + sessionId + "; Path=/; HttpOnly";
        redirect(output, "/?message=" + encodeUrl("登录成功，欢迎你：" + account.getName()), cookie);
    }

    /**
     * 处理开户表单。
     * 金额格式错误、账户重复、初始余额非法都会返回明确提示。
     */
    private void handleCreateAccount(HttpRequest request, OutputStream output) throws IOException {
        String accountId = request.parameter("accountId").trim();
        String name = request.parameter("name").trim();
        String password = request.parameter("password");
        Double balance = parseMoney(request.parameter("balance"));

        if (accountId.isEmpty() || name.isEmpty() || password.isEmpty()) {
            redirect(output, "/?message=" + encodeUrl("开户失败，账户号、姓名和密码不能为空。"), null);
            return;
        }

        if (balance == null) {
            redirect(output, "/?message=" + encodeUrl("开户失败，初始余额格式不正确。"), null);
            return;
        }

        boolean success = atm.createAccount(accountId, name, password, balance);
        String message = success ? "开户成功。" : "开户失败，可能是账户号重复或初始余额非法。";
        redirect(output, "/?message=" + encodeUrl(message), null);
    }

    /**
     * 处理存款请求。
     * 先验证登录状态和金额格式，再调用 ATM 业务方法。
     */
    private void handleDeposit(HttpRequest request, OutputStream output) throws IOException {
        Account currentAccount = getCurrentAccount(request);
        if (currentAccount == null) {
            redirect(output, "/?message=" + encodeUrl("请先登录账户。"), null);
            return;
        }

        Double amount = parseMoney(request.parameter("amount"));
        String message = amount == null ? "存款金额格式不正确。" : atm.deposit(currentAccount, amount);
        redirect(output, "/?message=" + encodeUrl(message), null);
    }

    /**
     * 处理取款请求。
     * 业务层会继续校验余额是否足够，访问层只负责基础格式校验。
     */
    private void handleWithdraw(HttpRequest request, OutputStream output) throws IOException {
        Account currentAccount = getCurrentAccount(request);
        if (currentAccount == null) {
            redirect(output, "/?message=" + encodeUrl("请先登录账户。"), null);
            return;
        }

        Double amount = parseMoney(request.parameter("amount"));
        String message = amount == null ? "取款金额格式不正确。" : atm.withdraw(currentAccount, amount);
        redirect(output, "/?message=" + encodeUrl(message), null);
    }

    /**
     * 处理转账请求。
     * 目标账户不存在、余额不足、给自己转账等规则仍然由 ATM 业务类统一处理。
     */
    private void handleTransfer(HttpRequest request, OutputStream output) throws IOException {
        Account currentAccount = getCurrentAccount(request);
        if (currentAccount == null) {
            redirect(output, "/?message=" + encodeUrl("请先登录账户。"), null);
            return;
        }

        String targetId = request.parameter("targetId").trim();
        Double amount = parseMoney(request.parameter("amount"));
        String message = amount == null ? "转账金额格式不正确。" : atm.transfer(currentAccount, targetId, amount);
        redirect(output, "/?message=" + encodeUrl(message), null);
    }

    /**
     * 处理退出登录。
     * 服务端删除会话，浏览器端 Cookie 设置为立即过期。
     */
    private void handleLogout(HttpRequest request, OutputStream output) throws IOException {
        String sessionId = request.cookie(SESSION_COOKIE_NAME);
        if (!sessionId.isEmpty()) {
            sessions.remove(sessionId);
        }

        String cookie = SESSION_COOKIE_NAME + "=; Path=/; Max-Age=0; HttpOnly";
        redirect(output, "/?message=" + encodeUrl("已退出当前账户。"), cookie);
    }

    /**
     * 根据 Cookie 中的会话编号获取当前账户。
     * 如果数据库中账户已不存在，会自动清理无效会话。
     */
    private Account getCurrentAccount(HttpRequest request) {
        String sessionId = request.cookie(SESSION_COOKIE_NAME);
        if (sessionId.isEmpty()) {
            return null;
        }

        String accountId = sessions.get(sessionId);
        if (accountId == null) {
            return null;
        }

        Account account = atm.findAccount(accountId);
        if (account == null) {
            sessions.remove(sessionId);
        }
        return account;
    }

    /**
     * 渲染首页主体内容。
     * 根据是否登录展示不同操作区域，保证浏览器访问就是完整 ATM 操作界面。
     */
    private String renderHomeContent(Account currentAccount) {
        StringBuilder html = new StringBuilder();
        html.append("<section class=\"panel hero\">");
        html.append("<div>");
        html.append("<p class=\"eyebrow\">Socket ATM</p>");
        html.append("<h1>浏览器 ATM 机</h1>");
        html.append("<p>账户数据存储在 SQLite 数据库 atm.db 中，页面请求由 Java ServerSocket 处理。</p>");
        html.append("</div>");
        if (currentAccount == null) {
            html.append("<span class=\"status\">未登录</span>");
        } else {
            html.append("<span class=\"status success\">已登录：").append(escapeHtml(currentAccount.getName())).append("</span>");
        }
        html.append("</section>");

        if (currentAccount == null) {
            html.append(renderLoginForms());
        } else {
            html.append(renderAccountActions(currentAccount));
        }

        html.append(renderAccountTable());
        return html.toString();
    }

    /**
     * 渲染登录和开户表单。
     * 两个表单都使用 POST，避免密码或开户信息暴露在地址栏中。
     */
    private String renderLoginForms() {
        StringBuilder html = new StringBuilder();
        html.append("<div class=\"grid two\">");
        html.append("<section class=\"panel\">");
        html.append("<h2>账户登录</h2>");
        html.append("<form method=\"post\" action=\"/login\">");
        html.append(input("账户号", "accountId", "text", "1001"));
        html.append(input("密码", "password", "password", "123456"));
        html.append("<button type=\"submit\">登录</button>");
        html.append("</form>");
        html.append("</section>");

        html.append("<section class=\"panel\">");
        html.append("<h2>新账户开户</h2>");
        html.append("<form method=\"post\" action=\"/create\">");
        html.append(input("账户号", "accountId", "text", ""));
        html.append(input("姓名", "name", "text", ""));
        html.append(input("密码", "password", "password", ""));
        html.append(input("初始余额", "balance", "number", "0"));
        html.append("<button type=\"submit\">开户</button>");
        html.append("</form>");
        html.append("</section>");
        html.append("</div>");
        return html.toString();
    }

    /**
     * 渲染登录后的账户操作区。
     * 存款、取款、转账都通过表单提交到对应 POST 路径。
     */
    private String renderAccountActions(Account account) {
        StringBuilder html = new StringBuilder();
        html.append("<section class=\"panel balance\">");
        html.append("<div>");
        html.append("<p class=\"eyebrow\">当前账户</p>");
        html.append("<h2>").append(escapeHtml(account.getAccountId())).append(" / ").append(escapeHtml(account.getName())).append("</h2>");
        html.append("</div>");
        html.append("<strong>").append(String.format("%.2f", account.getBalance())).append("</strong>");
        html.append("</section>");

        html.append("<div class=\"grid three\">");
        html.append(renderMoneyForm("存款", "/deposit", "存入金额"));
        html.append(renderMoneyForm("取款", "/withdraw", "取出金额"));

        html.append("<section class=\"panel\">");
        html.append("<h2>转账</h2>");
        html.append("<form method=\"post\" action=\"/transfer\">");
        html.append(input("目标账户号", "targetId", "text", ""));
        html.append(input("转账金额", "amount", "number", "0"));
        html.append("<button type=\"submit\">确认转账</button>");
        html.append("</form>");
        html.append("</section>");
        html.append("</div>");

        html.append("<form method=\"post\" action=\"/logout\" class=\"logout\">");
        html.append("<button type=\"submit\" class=\"secondary\">退出登录</button>");
        html.append("</form>");
        return html.toString();
    }

    /**
     * 渲染只包含金额输入框的业务表单。
     * 存款和取款结构相同，因此抽取此方法减少重复 HTML。
     */
    private String renderMoneyForm(String title, String action, String label) {
        StringBuilder html = new StringBuilder();
        html.append("<section class=\"panel\">");
        html.append("<h2>").append(escapeHtml(title)).append("</h2>");
        html.append("<form method=\"post\" action=\"").append(action).append("\">");
        html.append(input(label, "amount", "number", "0"));
        html.append("<button type=\"submit\">确认").append(escapeHtml(title)).append("</button>");
        html.append("</form>");
        html.append("</section>");
        return html.toString();
    }

    /**
     * 渲染账户列表。
     * 该区域保留原控制台“查看所有账户”的验收能力，但不展示密码。
     */
    private String renderAccountTable() {
        StringBuilder html = new StringBuilder();
        Account[] accounts = atm.getAllAccounts();
        int size = atm.getAccountCount();

        html.append("<section class=\"panel\">");
        html.append("<h2>账户列表</h2>");
        if (size == 0) {
            html.append("<p class=\"empty\">当前没有任何账户。</p>");
        } else {
            html.append("<table>");
            html.append("<thead><tr><th>账户号</th><th>姓名</th><th>余额</th></tr></thead>");
            html.append("<tbody>");
            for (int i = 0; i < size; i++) {
                html.append("<tr>");
                html.append("<td>").append(escapeHtml(accounts[i].getAccountId())).append("</td>");
                html.append("<td>").append(escapeHtml(accounts[i].getName())).append("</td>");
                html.append("<td>").append(String.format("%.2f", accounts[i].getBalance())).append("</td>");
                html.append("</tr>");
            }
            html.append("</tbody></table>");
        }
        html.append("</section>");
        return html.toString();
    }

    /**
     * 渲染完整 HTML 页面。
     * 页面样式内嵌在响应中，避免再额外处理静态资源请求。
     */
    private String renderLayout(String content, String message, Account currentAccount) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"zh-CN\"><head>");
        html.append("<meta charset=\"UTF-8\">");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
        html.append("<title>浏览器 ATM 机</title>");
        html.append("<style>");
        html.append("body{margin:0;background:#eef2f0;color:#17211c;font-family:Arial,'PingFang SC','Microsoft YaHei',sans-serif;}");
        html.append(".wrap{max-width:1120px;margin:0 auto;padding:28px 18px 48px;}");
        html.append(".panel{background:#fff;border:1px solid #d8dfda;border-radius:8px;padding:20px;box-shadow:0 8px 24px rgba(23,33,28,.06);}");
        html.append(".hero{display:flex;align-items:center;justify-content:space-between;gap:18px;background:#17352b;color:#fff;}");
        html.append(".hero p{margin:6px 0 0;color:#d9e7df;}");
        html.append(".eyebrow{margin:0 0 6px;font-size:12px;font-weight:700;text-transform:uppercase;color:#537566;}");
        html.append("h1,h2{margin:0;}h1{font-size:34px;}h2{font-size:20px;}");
        html.append(".status{display:inline-flex;align-items:center;border:1px solid #9eb6ab;border-radius:999px;padding:8px 12px;background:#f4f8f5;color:#17352b;font-weight:700;white-space:nowrap;}");
        html.append(".success{background:#dff3e7;border-color:#9ed0b2;}");
        html.append(".message{margin:18px 0;padding:12px 14px;border-left:4px solid #1d6f52;background:#effaf3;border-radius:6px;}");
        html.append(".grid{display:grid;gap:18px;margin-top:18px;}.two{grid-template-columns:repeat(2,minmax(0,1fr));}.three{grid-template-columns:repeat(3,minmax(0,1fr));}");
        html.append("form{display:grid;gap:12px;margin-top:14px;}label{display:grid;gap:7px;font-weight:700;font-size:14px;}");
        html.append("input{height:42px;border:1px solid #c8d1cb;border-radius:6px;padding:0 11px;font-size:15px;}");
        html.append("button{height:42px;border:0;border-radius:6px;background:#1d6f52;color:#fff;font-weight:700;font-size:15px;cursor:pointer;}");
        html.append("button.secondary{background:#46534d;}.logout{display:flex;justify-content:flex-end;margin-top:18px;}");
        html.append(".balance{display:flex;align-items:center;justify-content:space-between;margin-top:18px;}.balance strong{font-size:32px;color:#1d6f52;}");
        html.append("table{width:100%;border-collapse:collapse;margin-top:14px;}th,td{text-align:left;border-bottom:1px solid #e2e8e4;padding:11px;}th{background:#f4f7f5;}");
        html.append(".empty{color:#617169;}@media(max-width:760px){.hero,.balance{align-items:flex-start;flex-direction:column}.two,.three{grid-template-columns:1fr}h1{font-size:28px}}");
        html.append("</style></head><body><main class=\"wrap\">");
        if (message != null && !message.isEmpty()) {
            html.append("<div class=\"message\">").append(escapeHtml(message)).append("</div>");
        }
        html.append(content);
        html.append("</main></body></html>");
        return html.toString();
    }

    /**
     * 生成统一的输入框 HTML。
     * number 类型使用 step=0.01，便于输入带小数的金额。
     */
    private String input(String label, String name, String type, String value) {
        StringBuilder html = new StringBuilder();
        html.append("<label>").append(escapeHtml(label));
        html.append("<input name=\"").append(escapeHtml(name)).append("\" type=\"").append(escapeHtml(type)).append("\"");
        if ("number".equals(type)) {
            html.append(" step=\"0.01\" min=\"0\"");
        }
        html.append(" value=\"").append(escapeHtml(value)).append("\" required>");
        html.append("</label>");
        return html.toString();
    }

    /**
     * 解析金额。
     * 金额必须是合法数字，具体是否大于 0 交给 ATM 业务层继续校验。
     */
    private Double parseMoney(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * 发送 HTML 响应。
     * 响应头明确指定 UTF-8，保证中文页面在浏览器中正常显示。
     */
    private void sendHtml(OutputStream output, int statusCode, String html, String cookie) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        StringBuilder response = new StringBuilder();
        response.append("HTTP/1.1 ").append(statusCode).append(" ").append(statusText(statusCode)).append("\r\n");
        response.append("Content-Type: text/html; charset=UTF-8\r\n");
        response.append("Content-Length: ").append(body.length).append("\r\n");
        response.append("Connection: close\r\n");
        if (cookie != null) {
            response.append("Set-Cookie: ").append(cookie).append("\r\n");
        }
        response.append("\r\n");

        output.write(response.toString().getBytes(StandardCharsets.ISO_8859_1));
        output.write(body);
        output.flush();
    }

    /**
     * 发送重定向响应。
     * 表单提交后重定向回首页，避免刷新浏览器时重复提交同一笔业务。
     */
    private void redirect(OutputStream output, String location, String cookie) throws IOException {
        StringBuilder response = new StringBuilder();
        response.append("HTTP/1.1 303 See Other\r\n");
        response.append("Location: ").append(location).append("\r\n");
        response.append("Content-Length: 0\r\n");
        response.append("Connection: close\r\n");
        if (cookie != null) {
            response.append("Set-Cookie: ").append(cookie).append("\r\n");
        }
        response.append("\r\n");
        output.write(response.toString().getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
    }

    /**
     * 对 favicon 等不需要内容的请求返回 204。
     */
    private void sendNoContent(OutputStream output) throws IOException {
        String response = "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
        output.write(response.getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
    }

    private String statusText(int statusCode) {
        if (statusCode == 200) {
            return "OK";
        }
        if (statusCode == 404) {
            return "Not Found";
        }
        if (statusCode == 500) {
            return "Internal Server Error";
        }
        return "OK";
    }

    /**
     * 转义 HTML 特殊字符。
     * 所有用户输入展示到页面前都经过这里，避免把输入内容当成 HTML 执行。
     */
    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }

        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    /**
     * URL 编码提示消息。
     * 重定向时把提示放到查询参数里，因此需要先编码中文和特殊字符。
     */
    private String encodeUrl(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

/**
 * 程序入口类。
 * 当前入口启动浏览器版 ATM：Java 负责监听 socket，浏览器负责展示交互页面。
 */
public class ATMSystem {
    public static void main(String[] args) {
        int port = parsePort(args);

        /*
         * 当前需求指定使用数据库存储，因此入口直接使用 SQLite 仓库。
         * 文件存储实现仍保留在代码中，说明存储层可以继续按接口扩展或切换。
         */
        AccountRepository repository = new SqliteAccountRepository("atm.db", 5);
        ATM atm = new ATM(repository);

        /*
         * 预置测试账户。
         * 如果数据库中已经存在同名账户，addAccount 会返回 false，因此重复启动不会覆盖已有余额。
         */
        atm.createAccount("1001", "张三", "123456", 1000);
        atm.createAccount("1002", "李四", "123456", 2000);
        atm.createAccount("1003", "王五", "123456", 3000);

        ATMHttpServer server = new ATMHttpServer(atm, port);
        server.start();
    }

    /**
     * 解析启动端口。
     * 未传参数时默认使用 8080；端口格式错误时也回退到 8080，避免程序直接异常退出。
     */
    private static int parsePort(String[] args) {
        if (args == null || args.length == 0) {
            return 8080;
        }

        try {
            int port = Integer.parseInt(args[0]);
            if (port < 1 || port > 65535) {
                System.out.println("端口范围无效，已使用默认端口 8080。");
                return 8080;
            }
            return port;
        } catch (NumberFormatException exception) {
            System.out.println("端口格式无效，已使用默认端口 8080。");
            return 8080;
        }
    }
}
