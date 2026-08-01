package ug.co.moneyiq;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private final int ink = Color.rgb(24, 39, 45);
    private final int navy = Color.rgb(16, 42, 47);
    private final int teal = Color.rgb(16, 214, 163);
    private final int tealDark = Color.rgb(0, 143, 120);
    private final int bg = Color.rgb(238, 247, 245);
    private final int muted = Color.rgb(106, 123, 130);
    private final int border = Color.rgb(213, 228, 228);
    private final int danger = Color.rgb(211, 63, 82);
    private final int success = Color.rgb(25, 153, 99);

    private MoneyDb db;
    private LinearLayout root;
    private LinearLayout content;
    private Button dashboardTab;
    private Button addTab;
    private Button transactionsTab;
    private Button manageTab;
    private String selectedType = "expense";
    private final DecimalFormat money = new DecimalFormat("#,##0");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = new MoneyDb(this);
        db.seedDefaults();
        buildShell();
        showDashboard();
    }

    private void buildShell() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setLayoutParams(matchParent());

        root.addView(buildHeader());
        root.addView(buildTabs());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(14), dp(14), dp(24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        setContentView(root);
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(20), dp(20), dp(18));
        header.setBackgroundColor(navy);

        TextView name = new TextView(this);
        name.setText("MoneyIQ");
        name.setTextColor(Color.WHITE);
        name.setTextSize(30);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(name);

        TextView tagline = new TextView(this);
        tagline.setText("Know your money better");
        tagline.setTextColor(Color.rgb(185, 225, 217));
        tagline.setTextSize(14);
        tagline.setPadding(0, dp(4), 0, 0);
        header.addView(tagline);

        return header;
    }

    private View buildTabs() {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(12), dp(10), dp(12), dp(10));
        tabs.setBackgroundColor(Color.WHITE);

        dashboardTab = tabButton("Overview");
        addTab = tabButton("Add");
        transactionsTab = tabButton("Records");
        manageTab = tabButton("Manage");

        dashboardTab.setOnClickListener(v -> showDashboard());
        addTab.setOnClickListener(v -> showAddTransaction());
        transactionsTab.setOnClickListener(v -> showTransactions());
        manageTab.setOnClickListener(v -> showManage());

        tabs.addView(dashboardTab, tabParams());
        tabs.addView(addTab, tabParams());
        tabs.addView(transactionsTab, tabParams());
        tabs.addView(manageTab, tabParams());
        return tabs;
    }

    private Button tabButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setPadding(dp(6), dp(8), dp(6), dp(8));
        return b;
    }

    private LinearLayout.LayoutParams tabParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(44), 1);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private void selectTab(Button selected) {
        styleTab(dashboardTab, dashboardTab == selected);
        styleTab(addTab, addTab == selected);
        styleTab(transactionsTab, transactionsTab == selected);
        styleTab(manageTab, manageTab == selected);
    }

    private void styleTab(Button b, boolean active) {
        b.setTextColor(active ? Color.WHITE : tealDark);
        b.setBackground(round(active ? teal : Color.rgb(236, 252, 248), active ? teal : Color.rgb(168, 236, 222), 12));
    }

    private void showDashboard() {
        selectTab(dashboardTab);
        content.removeAllViews();

        Summary summary = db.getMonthSummary();
        content.addView(sectionTitle("This month"));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.addView(metricCard("Income", summary.income, success));
        row.addView(metricCard("Expenses", summary.expense, danger));
        row.addView(metricCard("Balance", summary.income - summary.expense, tealDark));
        content.addView(row);

        content.addView(sectionTitle("Accounts"));
        for (Account account : db.getAccounts()) {
            content.addView(accountCard(account));
        }

        content.addView(sectionTitle("Recent activity"));
        List<Transaction> recent = db.getTransactions(5);
        if (recent.isEmpty()) {
            content.addView(emptyState("No transactions yet. Tap Add to record your first income or expense."));
        } else {
            for (Transaction t : recent) content.addView(transactionRow(t));
        }
    }

    private View metricCard(String label, double amount, int color) {
        LinearLayout card = card();
        TextView l = label(label);
        TextView v = value(formatMoney(amount), color, 24);
        card.addView(l);
        card.addView(v);
        return card;
    }

    private View accountCard(Account account) {
        LinearLayout card = card();
        TextView name = value(account.name, ink, 17);
        TextView balance = value(formatMoney(account.balance), account.balance < 0 ? danger : tealDark, 20);
        balance.setPadding(0, dp(5), 0, 0);
        card.addView(name);
        card.addView(balance);
        return card;
    }

    private void showAddTransaction() {
        selectTab(addTab);
        content.removeAllViews();
        content.addView(sectionTitle("Add transaction"));

        LinearLayout card = card();
        LinearLayout typeRow = new LinearLayout(this);
        typeRow.setOrientation(LinearLayout.HORIZONTAL);
        Button expenseBtn = typeButton("Expense");
        Button incomeBtn = typeButton("Income");
        typeRow.addView(expenseBtn, tabParams());
        typeRow.addView(incomeBtn, tabParams());
        card.addView(typeRow);

        Spinner accountSpinner = spinner(db.getAccountNames());
        Spinner categorySpinner = spinner(db.getCategoryNames(selectedType));
        EditText amountInput = input("Amount");
        amountInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText noteInput = input("Note (optional)");

        card.addView(fieldLabel("Account"));
        card.addView(accountSpinner);
        card.addView(fieldLabel("Category"));
        card.addView(categorySpinner);
        card.addView(fieldLabel("Amount"));
        card.addView(amountInput);
        card.addView(fieldLabel("Note"));
        card.addView(noteInput);

        Runnable refreshType = () -> {
            expenseBtn.setBackground(round(selectedType.equals("expense") ? danger : Color.rgb(251, 235, 237), danger, 12));
            expenseBtn.setTextColor(selectedType.equals("expense") ? Color.WHITE : danger);
            incomeBtn.setBackground(round(selectedType.equals("income") ? success : Color.rgb(232, 248, 240), success, 12));
            incomeBtn.setTextColor(selectedType.equals("income") ? Color.WHITE : success);
            setSpinnerItems(categorySpinner, db.getCategoryNames(selectedType));
        };
        expenseBtn.setOnClickListener(v -> { selectedType = "expense"; refreshType.run(); });
        incomeBtn.setOnClickListener(v -> { selectedType = "income"; refreshType.run(); });
        refreshType.run();

        Button save = primaryButton("Save transaction");
        save.setOnClickListener(v -> {
            String amountText = amountInput.getText().toString().trim();
            if (amountText.isEmpty()) {
                toast("Enter an amount.");
                return;
            }
            double amount;
            try {
                amount = Double.parseDouble(amountText);
            } catch (NumberFormatException ex) {
                toast("Amount is not valid.");
                return;
            }
            if (amount <= 0) {
                toast("Amount must be greater than zero.");
                return;
            }
            String account = String.valueOf(accountSpinner.getSelectedItem());
            String category = String.valueOf(categorySpinner.getSelectedItem());
            db.addTransaction(selectedType, account, category, amount, noteInput.getText().toString().trim());
            hideKeyboard(amountInput);
            toast("Transaction saved.");
            showDashboard();
        });
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        saveParams.setMargins(0, dp(16), 0, 0);
        card.addView(save, saveParams);
        content.addView(card);
    }

    private Button typeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setAllCaps(false);
        return b;
    }

    private void showManage() {
        selectTab(manageTab);
        content.removeAllViews();
        content.addView(sectionTitle("Manage money setup"));

        LinearLayout accountCard = card();
        accountCard.addView(value("Add account", ink, 17));
        EditText accountName = input("Account name");
        EditText openingBalance = input("Opening balance (optional)");
        openingBalance.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        accountCard.addView(fieldLabel("Name"));
        accountCard.addView(accountName);
        accountCard.addView(fieldLabel("Opening balance"));
        accountCard.addView(openingBalance);
        Button saveAccount = primaryButton("Save account");
        LinearLayout.LayoutParams saveAccountParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        saveAccountParams.setMargins(0, dp(16), 0, 0);
        accountCard.addView(saveAccount, saveAccountParams);
        saveAccount.setOnClickListener(v -> {
            String name = accountName.getText().toString().trim();
            if (name.isEmpty()) {
                toast("Enter an account name.");
                return;
            }
            double opening = parseOptionalAmount(openingBalance.getText().toString().trim());
            if (Double.isNaN(opening)) {
                toast("Opening balance is not valid.");
                return;
            }
            if (db.addAccount(name, opening)) {
                hideKeyboard(accountName);
                toast("Account saved.");
                showManage();
            } else {
                toast("That account already exists.");
            }
        });
        content.addView(accountCard);

        LinearLayout categoryCard = card();
        categoryCard.addView(value("Add category", ink, 17));
        Spinner typeSpinner = spinner(listOf("expense", "income"));
        EditText categoryName = input("Category name");
        categoryCard.addView(fieldLabel("Type"));
        categoryCard.addView(typeSpinner);
        categoryCard.addView(fieldLabel("Name"));
        categoryCard.addView(categoryName);
        Button saveCategory = primaryButton("Save category");
        LinearLayout.LayoutParams saveCategoryParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        saveCategoryParams.setMargins(0, dp(16), 0, 0);
        categoryCard.addView(saveCategory, saveCategoryParams);
        saveCategory.setOnClickListener(v -> {
            String name = categoryName.getText().toString().trim();
            String type = String.valueOf(typeSpinner.getSelectedItem());
            if (name.isEmpty()) {
                toast("Enter a category name.");
                return;
            }
            if (db.addCategory(name, type)) {
                hideKeyboard(categoryName);
                toast("Category saved.");
                showManage();
            } else {
                toast("That category already exists.");
            }
        });
        content.addView(categoryCard);

        content.addView(sectionTitle("Current accounts"));
        for (Account account : db.getAccounts()) content.addView(accountCard(account));

        content.addView(sectionTitle("Expense categories"));
        content.addView(categoryList(db.getCategoryNames("expense")));
        content.addView(sectionTitle("Income categories"));
        content.addView(categoryList(db.getCategoryNames("income")));
    }

    private View categoryList(List<String> categories) {
        LinearLayout card = card();
        if (categories.isEmpty()) {
            card.addView(label("No categories yet."));
            return card;
        }
        for (String category : categories) {
            TextView item = value(category, ink, 15);
            item.setPadding(0, dp(4), 0, dp(4));
            card.addView(item);
        }
        return card;
    }

    private List<String> listOf(String first, String second) {
        List<String> items = new ArrayList<>();
        items.add(first);
        items.add(second);
        return items;
    }

    private double parseOptionalAmount(String text) {
        if (text.isEmpty()) return 0;
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }

    private void showTransactions() {
        selectTab(transactionsTab);
        content.removeAllViews();
        content.addView(sectionTitle("Transactions"));
        List<Transaction> records = db.getTransactions(100);
        if (records.isEmpty()) {
            content.addView(emptyState("No records yet."));
            return;
        }
        for (Transaction t : records) content.addView(transactionRow(t));
    }

    private View transactionRow(Transaction t) {
        LinearLayout card = card();
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = value(t.category, ink, 16);
        TextView amt = value((t.type.equals("expense") ? "-" : "+") + formatMoney(t.amount), t.type.equals("expense") ? danger : success, 16);
        amt.setGravity(Gravity.RIGHT);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(amt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(top);

        TextView meta = label(t.account + " • " + t.date + (t.note.isEmpty() ? "" : " • " + t.note));
        meta.setPadding(0, dp(6), 0, 0);
        card.addView(meta);
        return card;
    }

    private TextView sectionTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(ink);
        t.setTextSize(19);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(4), dp(8), dp(4), dp(8));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackground(round(Color.WHITE, border, 14));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, 0, dp(10));
        c.setLayoutParams(p);
        return c;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(muted);
        t.setTextSize(13);
        return t;
    }

    private TextView fieldLabel(String text) {
        TextView t = label(text);
        t.setPadding(0, dp(12), 0, dp(5));
        return t;
    }

    private TextView value(String text, int color, int size) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(size);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(ink);
        e.setHintTextColor(Color.rgb(139, 154, 160));
        e.setSingleLine(true);
        e.setPadding(dp(12), 0, dp(12), 0);
        e.setBackground(round(Color.rgb(248, 252, 252), border, 12));
        e.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        return e;
    }

    private Spinner spinner(List<String> items) {
        Spinner spinner = new Spinner(this);
        setSpinnerItems(spinner, items);
        spinner.setBackground(round(Color.rgb(248, 252, 252), border, 12));
        spinner.setPadding(dp(8), 0, dp(8), 0);
        spinner.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        return spinner;
    }

    private void setSpinnerItems(Spinner spinner, List<String> items) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    private Button primaryButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setBackground(round(teal, teal, 12));
        return b;
    }

    private View emptyState(String text) {
        TextView t = label(text);
        t.setPadding(dp(16), dp(16), dp(16), dp(16));
        t.setBackground(round(Color.WHITE, border, 14));
        return t;
    }

    private GradientDrawable round(int fill, int stroke, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        g.setStroke(dp(1), stroke);
        return g;
    }

    private String formatMoney(double amount) {
        return "UGX " + money.format(amount);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private void hideKeyboard(View view) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams matchParent() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    static class Account {
        final String name;
        final double balance;
        Account(String name, double balance) { this.name = name; this.balance = balance; }
    }

    static class Transaction {
        final String type, account, category, note, date;
        final double amount;
        Transaction(String type, String account, String category, double amount, String note, String date) {
            this.type = type;
            this.account = account;
            this.category = category;
            this.amount = amount;
            this.note = note;
            this.date = date;
        }
    }

    static class Summary {
        double income;
        double expense;
    }

    static class MoneyDb extends SQLiteOpenHelper {
        MoneyDb(Context context) { super(context, "moneyiq.db", null, 1); }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE accounts (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE NOT NULL, opening_balance REAL NOT NULL DEFAULT 0)");
            db.execSQL("CREATE TABLE categories (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, type TEXT NOT NULL)");
            db.execSQL("CREATE TABLE transactions (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, account TEXT NOT NULL, category TEXT NOT NULL, amount REAL NOT NULL, note TEXT, created_at TEXT NOT NULL)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

        void seedDefaults() {
            SQLiteDatabase db = getWritableDatabase();
            if (count(db, "accounts") == 0) {
                insertAccount(db, "Cash", 0);
                insertAccount(db, "Mobile Money", 0);
                insertAccount(db, "Bank", 0);
            }
            if (count(db, "categories") == 0) {
                insertCategory(db, "Food", "expense");
                insertCategory(db, "Transport", "expense");
                insertCategory(db, "Bills", "expense");
                insertCategory(db, "Shopping", "expense");
                insertCategory(db, "Salary", "income");
                insertCategory(db, "Business", "income");
                insertCategory(db, "Other Income", "income");
            }
        }

        private long count(SQLiteDatabase db, String table) {
            Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + table, null);
            try { return c.moveToFirst() ? c.getLong(0) : 0; }
            finally { c.close(); }
        }

        private void insertAccount(SQLiteDatabase db, String name, double opening) {
            ContentValues v = new ContentValues();
            v.put("name", name);
            v.put("opening_balance", opening);
            db.insert("accounts", null, v);
        }

        boolean addAccount(String name, double opening) {
            SQLiteDatabase db = getWritableDatabase();
            if (accountExists(db, name)) return false;
            insertAccount(db, name, opening);
            return true;
        }

        boolean addCategory(String name, String type) {
            SQLiteDatabase db = getWritableDatabase();
            if (categoryExists(db, name, type)) return false;
            insertCategory(db, name, type);
            return true;
        }

        private boolean accountExists(SQLiteDatabase db, String name) {
            Cursor c = db.rawQuery("SELECT 1 FROM accounts WHERE lower(name)=lower(?) LIMIT 1", new String[]{name});
            try { return c.moveToFirst(); }
            finally { c.close(); }
        }

        private boolean categoryExists(SQLiteDatabase db, String name, String type) {
            Cursor c = db.rawQuery("SELECT 1 FROM categories WHERE lower(name)=lower(?) AND type=? LIMIT 1", new String[]{name, type});
            try { return c.moveToFirst(); }
            finally { c.close(); }
        }
        private void insertCategory(SQLiteDatabase db, String name, String type) {
            ContentValues v = new ContentValues();
            v.put("name", name);
            v.put("type", type);
            db.insert("categories", null, v);
        }

        List<String> getAccountNames() {
            List<String> names = new ArrayList<>();
            Cursor c = getReadableDatabase().rawQuery("SELECT name FROM accounts ORDER BY id", null);
            try { while (c.moveToNext()) names.add(c.getString(0)); }
            finally { c.close(); }
            return names;
        }

        List<Account> getAccounts() {
            List<Account> accounts = new ArrayList<>();
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT a.name, a.opening_balance + " +
                            "COALESCE(SUM(CASE WHEN t.type='income' THEN t.amount WHEN t.type='expense' THEN -t.amount ELSE 0 END),0) " +
                            "FROM accounts a LEFT JOIN transactions t ON t.account=a.name GROUP BY a.id, a.name, a.opening_balance ORDER BY a.id",
                    null);
            try { while (c.moveToNext()) accounts.add(new Account(c.getString(0), c.getDouble(1))); }
            finally { c.close(); }
            return accounts;
        }

        List<String> getCategoryNames(String type) {
            List<String> names = new ArrayList<>();
            Cursor c = getReadableDatabase().rawQuery("SELECT name FROM categories WHERE type=? ORDER BY name", new String[]{type});
            try { while (c.moveToNext()) names.add(c.getString(0)); }
            finally { c.close(); }
            return names;
        }

        void addTransaction(String type, String account, String category, double amount, String note) {
            ContentValues v = new ContentValues();
            v.put("type", type);
            v.put("account", account);
            v.put("category", category);
            v.put("amount", amount);
            v.put("note", note == null ? "" : note);
            v.put("created_at", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()));
            getWritableDatabase().insert("transactions", null, v);
        }

        Summary getMonthSummary() {
            Summary s = new Summary();
            String month = new SimpleDateFormat("yyyy-MM", Locale.US).format(new Date()) + "%";
            Cursor c = getReadableDatabase().rawQuery("SELECT type, SUM(amount) FROM transactions WHERE created_at LIKE ? GROUP BY type", new String[]{month});
            try {
                while (c.moveToNext()) {
                    if ("income".equals(c.getString(0))) s.income = c.getDouble(1);
                    if ("expense".equals(c.getString(0))) s.expense = c.getDouble(1);
                }
            } finally { c.close(); }
            return s;
        }

        List<Transaction> getTransactions(int limit) {
            List<Transaction> records = new ArrayList<>();
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT type, account, category, amount, COALESCE(note,''), created_at FROM transactions ORDER BY id DESC LIMIT ?",
                    new String[]{String.valueOf(limit)});
            try {
                while (c.moveToNext()) {
                    records.add(new Transaction(c.getString(0), c.getString(1), c.getString(2), c.getDouble(3), c.getString(4), c.getString(5)));
                }
            } finally { c.close(); }
            return records;
        }
    }
}
