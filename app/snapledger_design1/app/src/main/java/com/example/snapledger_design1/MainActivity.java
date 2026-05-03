package com.example.snapledger_design1;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        //para makita yung texts and icons sa status bar ng phone, di kase kita e
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(true);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.home), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        //fragment ginamit ko para sa pag switch ng mga screen
        loadFragment(new HomeFragment());

        //bottom nav bar
        LinearLayout navHome = findViewById(R.id.nav_home);
        LinearLayout navScan = findViewById(R.id.nav_scan);
        LinearLayout navHistory = findViewById(R.id.nav_history);
        LinearLayout navBudget = findViewById(R.id.nav_budget);
        FloatingActionButton fabAdd = findViewById(R.id.fab_add);

        navHome.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadFragment(new HomeFragment());
                updateTabColors(v.getId());
            }
        });
        navScan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadFragment(new ScanFragment());
                updateTabColors(v.getId());
            }
        });
        navHistory.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadFragment(new HistoryFragment());
                updateTabColors(v.getId());
            }
        });
        navBudget.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadFragment(new BudgetFragment());
                updateTabColors(v.getId());
            }
        });
        fabAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
                transaction.replace(R.id.fragment_container, new AddTransactionFragment());
                transaction.addToBackStack(null);
                transaction.commit();
            }
        });
    }

    private void loadFragment(Fragment fragment) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
        fragmentTransaction.replace(R.id.fragment_container, fragment);
        fragmentTransaction.commit();
    }

    //change color ng nav bar icons and text
    private void updateTabColors(int activeTabId) {
        //initialize
        ImageView ivHome = findViewById(R.id.iv_home);
        ImageView ivScan = findViewById(R.id.iv_scan);
        ImageView ivHistory = findViewById(R.id.iv_history);
        ImageView ivBudget = findViewById(R.id.iv_budget);

        TextView tvHome = findViewById(R.id.tv_home);
        TextView tvScan = findViewById(R.id.tv_scan);
        TextView tvHistory = findViewById(R.id.tv_history);
        TextView tvBudget = findViewById(R.id.tv_budget);

        //reset colors to gray
        int gray = Color.parseColor("#757575");
        int green = Color.parseColor("#00A86B");

        ivHome.setColorFilter(gray);
        ivScan.setColorFilter(gray);
        ivHistory.setColorFilter(gray);
        ivBudget.setColorFilter(gray);

        tvHome.setTextColor(gray);
        tvScan.setTextColor(gray);
        tvHistory.setTextColor(gray);
        tvBudget.setTextColor(gray);

        //change active tab color
        if (activeTabId == R.id.nav_home) {
            ivHome.setColorFilter(green);
            tvHome.setTextColor(green);
        } else if (activeTabId == R.id.nav_scan) {
            ivScan.setColorFilter(green);
            tvScan.setTextColor(green);
        } else if (activeTabId == R.id.nav_history) {
            ivHistory.setColorFilter(green);
            tvHistory.setTextColor(green);
        } else if (activeTabId == R.id.nav_budget) {
            ivBudget.setColorFilter(green);
            tvBudget.setTextColor(green);
        }
    }
}