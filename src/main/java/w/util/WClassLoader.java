package w.util;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * @author Frank
 * @date 2024/4/4 17:09
 */
public class WClassLoader extends URLClassLoader {

    public WClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        return super.findClass(name);
    }
}