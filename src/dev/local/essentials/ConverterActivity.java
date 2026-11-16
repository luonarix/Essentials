package dev.local.essentials;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.Locale;

public class ConverterActivity extends Activity {

    private static final String[] CATS = {"Độ dài", "Khối lượng", "Nhiệt độ"};

    private static final String[] LEN = {"mm", "cm", "m", "km", "inch", "ft", "mile"};
    private static final double[] LEN_F = {0.001, 0.01, 1, 1000, 0.0254, 0.3048, 1609.344};

    private static final String[] WGHT = {"g", "kg", "tấn", "lb", "oz"};
    private static final double[] WGHT_F = {0.001, 1, 1000, 0.45359237, 0.028349523125};

    private static final String[] TEMP = {"°C", "°F", "K"};

    private Spinner catSpinner;
    private Spinner fromSpinner;
    private Spinner toSpinner;
    private EditText input;
    private TextView result;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 24));

        LinearLayout header = Ui.row(this);
        TextView back = Ui.text(this, "\u2039", 30, Ui.TEXT, false);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        header.addView(back, Ui.lp(Ui.dp(this, 40), Ui.dp(this, 40)));
        Ui.press(back, 0.88f);
        header.addView(Ui.text(this, "Quy đổi", 22, Ui.TEXT, true));
        root.addView(header);

        catSpinner = spinner(CATS);
        LinearLayout.LayoutParams cp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 50));
        cp.topMargin = Ui.dp(this, 18);
        root.addView(catSpinner, cp);

        LinearLayout card = Ui.col(this);
        card.setBackground(Ui.framed(Ui.SURFACE, this, 20));
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        LinearLayout.LayoutParams cardp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardp.topMargin = Ui.dp(this, 14);
        root.addView(card, cardp);

        input = new EditText(this);
        input.setBackground(null);
        input.setTextSize(24);
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.MUTED);
        input.setHint("0");
        input.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));

        fromSpinner = spinner(new String[]{""});

        LinearLayout row1 = Ui.row(this);
        row1.addView(input, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row1.addView(fromSpinner, Ui.lp(Ui.dp(this, 116), Ui.dp(this, 44)));
        card.addView(row1);

        card.addView(divider());

        result = Ui.text(this, "—", 24, Ui.ACCENT, true);
        toSpinner = spinner(new String[]{""});
        LinearLayout row2 = Ui.row(this);
        row2.addView(result, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row2.addView(toSpinner, Ui.lp(Ui.dp(this, 116), Ui.dp(this, 44)));
        card.addView(row2);

        TextView swap = Ui.text(this, "⇅  Đảo đơn vị", 14, Ui.MUTED, false);
        swap.setGravity(Gravity.CENTER);
        swap.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        swap.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int a = fromSpinner.getSelectedItemPosition();
                int b = toSpinner.getSelectedItemPosition();
                fromSpinner.setSelection(b);
                toSpinner.setSelection(a);
            }
        });
        root.addView(swap);

        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                convert();
            }
        });

        catSpinner.setOnItemSelectedListener(new SimpleSelect() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                loadUnits();
            }
        });
        fromSpinner.setOnItemSelectedListener(new SimpleSelect() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                convert();
            }
        });
        toSpinner.setOnItemSelectedListener(new SimpleSelect() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                convert();
            }
        });

        setContentView(root);
        Ui.ripple(catSpinner);
        Ui.ripple(fromSpinner);
        Ui.ripple(toSpinner);
        Ui.rise(header, 20, 200);
        Ui.rise(catSpinner, 70, 240);
        Ui.rise(card, 110, 260);
        Ui.rise(swap, 160, 240);
        Ui.press(swap, 0.94f);
        loadUnits();
    }

    private abstract static class SimpleSelect implements AdapterView.OnItemSelectedListener {
        public void onNothingSelected(AdapterView<?> p) {}
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0xFF232A36);
        LinearLayout.LayoutParams p = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, Ui.dp(this, 0.6f)));
        v.setLayoutParams(p);
        return v;
    }

    private Spinner spinner(String[] items) {
        Spinner s = new Spinner(this);
        s.setBackground(Ui.framed(Ui.SURFACE_HI, this, 14));
        s.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        setItems(s, items);
        return s;
    }

    private void setItems(Spinner s, final String[] items) {
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, items) {
            @Override
            public View getView(int pos, View convert, ViewGroup parent) {
                TextView tv = (TextView) super.getView(pos, convert, parent);
                tv.setTextColor(Ui.TEXT);
                tv.setTextSize(14);
                return tv;
            }

            @Override
            public View getDropDownView(int pos, View convert, ViewGroup parent) {
                TextView tv = (TextView) super.getDropDownView(pos, convert, parent);
                tv.setTextColor(Ui.TEXT);
                tv.setTextSize(15);
                tv.setPadding(Ui.dp(ConverterActivity.this, 16), Ui.dp(ConverterActivity.this, 12),
                        Ui.dp(ConverterActivity.this, 16), Ui.dp(ConverterActivity.this, 12));
                return tv;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(adapter);
    }

    private int category() {
        return Math.max(0, catSpinner.getSelectedItemPosition());
    }

    private void loadUnits() {
        int cat = category();
        String[] units = cat == 0 ? LEN : cat == 1 ? WGHT : TEMP;
        setItems(fromSpinner, units);
        setItems(toSpinner, units);
        toSpinner.setSelection(units.length > 1 ? 1 : 0);
        convert();
    }

    private void convert() {
        if (result == null) return;
        double v = parse(input.getText().toString());
        if (Double.isNaN(v)) {
            result.setText("—");
            return;
        }

        int cat = category();
        int from = fromSpinner.getSelectedItemPosition();
        int to = toSpinner.getSelectedItemPosition();
        if (from < 0 || to < 0) return;

        double out;
        if (cat == 2) {
            out = fromKelvin(toKelvin(cat, from, v), cat, to);
        } else {
            double[] f = cat == 0 ? LEN_F : WGHT_F;
            if (from >= f.length || to >= f.length || f[to] == 0) return;
            out = v * f[from] / f[to];
        }
        result.setText(fmt(out));
    }

    private double toKelvin(int cat, int unit, double v) {
        if (cat != 2) return v;
        if (unit == 0) return v + 273.15;        // °C
        if (unit == 1) return (v - 32) * 5 / 9 + 273.15; // °F
        return v;                                 // K
    }

    private double fromKelvin(double k, int cat, int unit) {
        if (cat != 2) return k;
        if (unit == 0) return k - 273.15;
        if (unit == 1) return (k - 273.15) * 9 / 5 + 32;
        return k;
    }

    private double parse(String s) {
        s = s.trim();
        if (s.isEmpty() || s.equals("-") || s.equals(".") || s.equals("-.")) return Double.NaN;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private String fmt(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "—";
        double a = Math.abs(v);
        if (a == 0) return "0";
        String s;
        if (a < 1e-4 || a >= 1e10) {
            s = String.format(Locale.US, "%.4e", v);
        } else {
            s = String.format(Locale.US, "%.6f", v);
            if (s.indexOf('.') >= 0) {
                s = s.replaceAll("0+$", "");
                s = s.replaceAll("\\.$", "");
            }
        }
        if (s.equals("-0")) s = "0";
        return s;
    }
}
