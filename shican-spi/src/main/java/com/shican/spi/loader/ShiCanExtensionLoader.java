package com.shican.spi.loader;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import com.shican.spi.annotation.ShiCanSpi;
import com.shican.spi.util.Holder;

public class ShiCanExtensionLoader<T> {

	/** shicanExtension点的路径 */
	private static final String SHICAN_DIRECTORY = "META-INF/SHICAN/";

	/**
	 * 分割SPI上默认拓展点字符串用的
	 */
	private static final Pattern NAME_SEPARATOR = Pattern.compile("\\s*[,]+\\s*");

	/** 扩展点加载器的缓存 */
	private static final ConcurrentHashMap<Class<?>, ShiCanExtensionLoader<?>> EXTENSION_LOADERS = new ConcurrentHashMap<Class<?>, ShiCanExtensionLoader<?>>();

	/** 扩展点加缓存 */
	private static final ConcurrentHashMap<Class<?>, Object> EXTENSION_INSTANCES = new ConcurrentHashMap<Class<?>, Object>();

	/**
	 * 接口的class
	 */
	private final Class<?> type;

	/** 保存接口ShiCanSpi注解上的值 */
	private String cachedDefaultName;

	/**
	 * 异常记录
	 */
	private Map<String, IllegalStateException> exceptions = new ConcurrentHashMap<String, IllegalStateException>();

	private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<Map<String, Class<?>>>();
	private final ConcurrentMap<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<String, Holder<Object>>();

	private static <T> boolean withExtensionAnnotation(Class<T> type) {
		return type.isAnnotationPresent(ShiCanSpi.class);
	}

	private ShiCanExtensionLoader(Class<T> type) {
		this.type = type;
	}

	public static <T> ShiCanExtensionLoader<T> getExtensionLoader(Class<T> type) {
		/***
		 * 判断type 接口参数
		 */
		if (null == type) {
			throw new IllegalArgumentException("Extension type == null");
		}
		if (!type.isInterface()) {
			throw new IllegalArgumentException("Extension type(" + type + ") is not interface!");
		}
		if (!withExtensionAnnotation(type)) {
			throw new IllegalArgumentException("Extension type(" + type + ") is not extension, because WITHOUT @"
					+ ShiCanSpi.class.getSimpleName() + " Annotation!");
		}

		ShiCanExtensionLoader<T> loader = (ShiCanExtensionLoader<T>) EXTENSION_LOADERS.get(type);
		if (null == loader) {
			EXTENSION_LOADERS.putIfAbsent(type, new ShiCanExtensionLoader<T>(type));
			loader = (ShiCanExtensionLoader<T>) EXTENSION_LOADERS.get(type);
		}
		return loader;
	}

	@SuppressWarnings("unchecked")
	public T getExtension(String name) {
		if (name == null || name.length() == 0)
			throw new IllegalArgumentException("Extension name == null");
		if ("true".equals(name)) {
			return getDefaultExtension();
		}
		Holder<Object> holder = cachedInstances.get(name);
		if (null == holder) {
			cachedInstances.putIfAbsent(name, new Holder<Object>());
			holder = cachedInstances.get(name);
		}
		Object instance = holder.getT();
		if (null == instance) {
			synchronized (holder) {
				instance = holder.getT();
				if (null == instance) {
					instance = createExtension(name);
					holder.setT(instance);
				}
			}

		}
		return (T) instance;
	}

	/**
	 * 根据获取到的拓展点class实例化成对象返回
	 * 
	 * @param name
	 * @return
	 */
	private T createExtension(String name) {
		Class<?> clazz = getExtensionClasses().get(name);
		if (clazz == null) {
			 throw findException(name);
		}
		try {
			 T instance = (T)  EXTENSION_INSTANCES.get(clazz);
			 if(null == instance){
				 EXTENSION_INSTANCES.putIfAbsent(clazz, (T) clazz.newInstance());//反射生成对象
				 instance = (T)  EXTENSION_INSTANCES.get(clazz);
			 }
			 return instance;
		} catch (Throwable t) {
            throw new IllegalStateException("Extension instance(name: " + name + ", class: " +
                    type + ")  could not be instantiated: " + t.getMessage(), t);
		}
	}

	/**
	 * 获取拓展点class,并缓存
	 * 
	 * @return
	 */
	private Map<String, Class<?>> getExtensionClasses() {
		Map<String, Class<?>> classes = cachedClasses.getT();
		if (null == classes) {
			synchronized (cachedClasses) {
				classes = cachedClasses.getT();
				if (null == classes) {
					classes = loadExtensionClasses();
					cachedClasses.setT(classes);
				}
			}
		}
		return classes;
	}

	/**
	 * 1.设置接口默认的实现类名 2.加载文件
	 * 
	 * @return
	 */
	private Map<String, Class<?>> loadExtensionClasses() {
		final ShiCanSpi defaultAnnotation = type.getAnnotation(ShiCanSpi.class);
		if (null != defaultAnnotation) {
			String value = defaultAnnotation.value(); // 拿到注解value --缺省值
			if (value != null && (value = value.trim()).length() > 0) {
				String[] names = NAME_SEPARATOR.split(value);
				if (names.length > 1) {
					throw new IllegalStateException("more than 1 default extension name on extension " + type.getName()
							+ ": " + Arrays.toString(names));
				}
				if (names.length == 1)
					cachedDefaultName = names[0];
			}
		}

		Map<String, Class<?>> extensionClasses = new HashMap<String, Class<?>>();
		loadFile(extensionClasses, SHICAN_DIRECTORY);
		return extensionClasses;
	}

	/**
	 * 加载本地扩展文件
	 * 
	 * @param extensionClasses
	 * @param shicanDirectory
	 */
	private void loadFile(Map<String, Class<?>> extensionClasses, String dir) {
		String fileName = dir + type.getName(); // 固定文件夹 + 接口名全路径

		// 对本地spi扩展文件操作
		try {
			Enumeration<URL> urls;
			ClassLoader classLoader = findClassLoader();
			if (null != classLoader) {
				urls = classLoader.getResources(fileName);
			} else {
				urls = ClassLoader.getSystemResources(fileName);
			}
			if (null != urls) {
				while (urls.hasMoreElements()) {
					java.net.URL url = urls.nextElement();
					try {
						BufferedReader bufferedReader = new BufferedReader(
								new InputStreamReader(url.openStream(), "UTF-8"));
						try {
							String line = null;
							while ((line = bufferedReader.readLine()) != null) {
								final int ci = line.indexOf('#');
								if (ci > 0)
									line = line.substring(0, ci);
								line = line.trim();
								if (line.length() > 0) {
									try {
										String name = null;
										int i = line.indexOf("=");
										if (i > 0) {
											name = line.substring(0, i).trim();
											line = line.substring(i + 1).trim();
										}
										if (line.length() > 0) {
											Class<?> clazz = Class.forName(line, true, classLoader); // 鼓掌!这里终于得到spi(classs)
											if (!type.isAssignableFrom(clazz)) {
												throw new IllegalStateException(
														"Error when load extension class(interface: " + type
																+ ", class line: " + clazz.getName() + "), class "
																+ clazz.getName() + "is not subtype of interface.");
											}
											extensionClasses.put(name, clazz);// 加入缓存
										}
									} catch (Throwable t) {
										IllegalStateException e = new IllegalStateException(
												"Failed to load extension class(interface: " + type + ", class line: "
														+ line + ") in " + url + ", cause: " + t.getMessage(),
												t);
										exceptions.put(line, e);
									}
								}
							}
						} finally {
							bufferedReader.close();
						}
					} catch (Exception e) {
						// ignore...
					}
				}
			}
		} catch (Exception e) {
			// ignore...
		}

	}

	private static ClassLoader findClassLoader() {
		return ShiCanExtensionLoader.class.getClassLoader();
	}

	public T getDefaultExtension() {
		getExtensionClasses();
		if (null == cachedDefaultName || cachedDefaultName.length() == 0 || "true".equals(cachedDefaultName)) {
			return null;
		}
		return getExtension(cachedDefaultName);
	}

	// 异常提示
	private IllegalStateException findException(String name) {
		for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
			if (entry.getKey().toLowerCase().contains(name.toLowerCase())) {
				return entry.getValue();
			}
		}
		StringBuilder buf = new StringBuilder("No such extension " + type.getName() + " by name " + name);

		int i = 1;
		for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
			if (i == 1) {
				buf.append(", possible causes: ");
			}

			buf.append("\r\n(");
			buf.append(i++);
			buf.append(") ");
			buf.append(entry.getKey());
			buf.append(":\r\n");
			buf.append(entry.getValue().toString());
		}
		return new IllegalStateException(buf.toString());
	}
}
