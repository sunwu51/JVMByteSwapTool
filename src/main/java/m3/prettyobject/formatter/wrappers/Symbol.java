package m3.prettyobject.formatter.wrappers;


/**
 * Symbol is just like a string, used internally to avoid
 * printing quotation marks around special strings such as "["
 * and "}" when passing to the formatter.
 */
public class Symbol {
    private final String symbol;

    public Symbol(String symbol) {
        this.symbol = symbol;
    }

    public String getSymbol() {
        return symbol;
    }

    @Override
    public String toString() {
        return symbol;
    }
}
