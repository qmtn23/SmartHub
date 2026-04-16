import java.util.*;

public class Main {
    static int[] r, s;
    static int mx;
    static int fd(int x) {
        while (r[x] != x) { r[x] = r[r[x]]; x = r[x]; }
        return x;
    }
    static void mg(int a, int b) {
        int u = fd(a), v = fd(b);
        if (u == v) return;
        if (s[u] < s[v]) { int t = u; u = v; v = t; }
        r[v] = u; s[u] += s[v];
        if (s[u] > mx) mx = s[u];
    }
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int T = sc.nextInt();
        StringBuilder out = new StringBuilder();
        while (T-- > 0) {
            int n = sc.nextInt();
            int[] a = new int[n];
            for (int i = 0; i < n; i++) a[i] = sc.nextInt();
            if (n == 1) { out.append("1\n"); continue; }
            List<List<Integer>> b = new ArrayList<>();
            for (int i = 0; i <= n; i++) b.add(new ArrayList<>());
            for (int i = 0; i + 1 < n; i++) b.get(Math.abs(a[i + 1] - a[i])).add(i);
            r = new int[n]; s = new int[n];
            for (int i = 0; i < n; i++) { r[i] = i; s[i] = 1; }
            mx = 1;
            for (int idx : b.get(0)) mg(idx, idx + 1);
            for (int k = 1; k <= n; k++) {
                for (int idx : b.get(k)) mg(idx, idx + 1);
                if (k > 1) out.append(' ');
                out.append(mx);
            }
            out.append('\n');
        }
        System.out.print(out);
    }
}
