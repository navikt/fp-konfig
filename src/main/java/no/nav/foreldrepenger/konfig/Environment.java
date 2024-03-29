package no.nav.foreldrepenger.konfig;

import static java.lang.System.getenv;

import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import no.nav.foreldrepenger.konfig.KonfigVerdi.*;

public final class Environment {

    static final class Init {
        // Josh Bloch's lazy load singleton (ref "Effective Java").
        // Siden Init ikke lastes før den referes blir feltet her initiert først når den aksesseres første gang.
        static final Environment CURRENT = of(Cluster.current(), Namespace.current(), Application.current(),
            ClientId.current(), currentImage());

        private Init() {
        }

        private static Environment of(Cluster cluster, Namespace namespace, Application application,
                                      ClientId clientId, String image) {
            var ensureClientId = Optional.ofNullable(clientId).orElseGet(() -> composeClientId(cluster, namespace, application));
            return new Environment(cluster, namespace, application, ensureClientId, image);
        }

        private static ClientId composeClientId(Cluster cluster, Namespace namespace, Application application) {
            return ClientId.of(cluster.clusterName() + ":" + namespace.getName() + ":" + application.getName());
        }

        private static String currentImage() {
            return getenv(NaisProperty.IMAGE.propertyName());
        }

    }

    private final Cluster cluster;
    private final Namespace namespace;
    private final Application application;
    private final ClientId clientId;
    private final String imageName;
    private final List<KonfigVerdiProvider> propertySources;

    private Environment(Cluster cluster, Namespace namespace, Application application, ClientId clientId, String imageName) {
        this.cluster = cluster;
        this.namespace = namespace;
        this.application = application;
        this.clientId = clientId;
        this.imageName = imageName;
        this.propertySources = List.of(
            new SystemPropertiesKonfigVerdiProvider(),
            new EnvPropertiesKonfigVerdiProvider(),
            new ApplicationPropertiesKonfigProvider());
    }

    public static Environment current() {
        return Init.CURRENT;
    }

    public Cluster getCluster() {
        return cluster;
    }

    public Namespace getNamespace() {
        return namespace;
    }

    public Application getApplication() {
        return application;
    }

    public ClientId getClientId() {
        return clientId;
    }

    public boolean isProd() {
        return cluster.isProd();
    }

    public boolean isDev() {
        return cluster.isDev();
    }

    public boolean isVTP() {
        return cluster.isVTP();
    }

    public boolean isLocal() {
        return cluster.isLocal();
    }

    public boolean isFss() {
        return cluster.isFss();
    }

    public boolean isGcp() {
        return cluster.isGcp();
    }

    public String clusterName() {
        return cluster.clusterName();
    }

    public String namespace() {
        return namespace.getName();
    }

    public String application() {
        return application.getName();
    }

    public String clientId() {
        return clientId.getClientId();
    }

    public String imageName() {
        return imageName;
    }

    public String getNaisAppName() {
        return getProperty(NaisProperty.APPLICATION.propertyName(), "vtp");
    }

    public String getTruststorePath() {
        return getenv(NaisProperty.TRUSTSTORE_PATH.propertyName());
    }

    public String getTruststorePassword() {
        return getenv(NaisProperty.TRUSTSTORE_PASSWORD.propertyName());
    }

    public Properties getPropertiesWithPrefix(String prefix) {
        Properties props = new Properties();
        props.putAll(getProperties(StandardPropertySource.SYSTEM_PROPERTIES).getVerdier());
        props.putAll(getProperties(StandardPropertySource.ENV_PROPERTIES).getVerdier());
        props.putAll(getProperties(StandardPropertySource.APP_PROPERTIES).getVerdier());

        var filtered = new Properties();
        filtered.putAll(props.entrySet()
            .stream()
            .filter(k -> k.getKey().toString().startsWith(prefix))
            .collect(
                Collectors.toMap(
                    e -> (String) e.getKey(),
                    e -> (String) e.getValue())));
        return filtered;
    }

    public PropertySourceMetaData getProperties(StandardPropertySource source) {
        return propertySources.stream()
            .filter(p -> p.getSource().equals(source))
            .findFirst()
            .map(KonfigVerdiProvider::getAllProperties)
            .orElseThrow();
    }

    public List<KonfigVerdiProvider> getPropertySources() {
        return propertySources;
    }

    public String getProperty(String key, String defaultVerdi) {
        return getProperty(key, String.class, defaultVerdi);
    }

    public <T> T getProperty(String key, Class<T> targetType) {
        return getProperty(key, targetType, null);
    }

    public String getRequiredProperty(String key) {
        return getRequiredProperty(key, () -> new IllegalStateException(key + " ble ikke funnet"));
    }

    public String getRequiredProperty(String key, Supplier<? extends RuntimeException> exceptionSupplier) {
        return Optional.ofNullable(getProperty(key))
            .orElseThrow(exceptionSupplier);
    }

    public <T> T getRequiredProperty(String key, Class<T> targetType) {
        return Optional.ofNullable(getProperty(key, targetType))
            .orElseThrow(() -> new IllegalStateException(key + " ble ikke funnet"));
    }

    @SuppressWarnings("unchecked")
    public <T> T getProperty(String key, Class<T> targetType, T defaultVerdi) {
        var converter = converterFor(targetType);
        if (converter == null && !targetType.equals(String.class)) {
            throw new IllegalArgumentException("Konvertering til " + targetType + " er ikke støttet");
        }

        return propertySources.stream()
            .filter(s -> s.harVerdi(key))
            .map(s -> s.getVerdi(key, converter))
            .filter(Objects::nonNull)
            .findFirst()
            .map(v -> (T) v)
            .orElse(defaultVerdi);
    }

    public String getProperty(String key) {
        return getProperty(key, (String) null);
    }

    private static <T> Converter<?> converterFor(Class<T> targetType) {
        try {
            if (targetType.equals(String.class)) {
                return construct(NoConverter.class);
            }
            if (targetType.equals(Period.class)) {
                return construct(PeriodConverter.class);
            }
            if (targetType.equals(Duration.class)) {
                return construct(DurationConverter.class);
            }
            if (targetType.equals(LocalDate.class)) {
                return construct(LocalDateConverter.class);
            }
            if (targetType.equals(Long.class)) {
                return construct(LongConverter.class);
            }
            if (targetType.equals(Boolean.class) || targetType.equals(boolean.class)) {
                return construct(BooleanConverter.class);
            }
            if (targetType.equals(URI.class)) {
                return construct(UriConverter.class);
            }
            if (targetType.equals(URL.class)) {
                return construct(UrlConverter.class);
            }
            if (targetType.equals(Integer.class) || targetType.equals(int.class)) {
                return construct(IntegerConverter.class);
            }
            if (targetType.equals(Long.class) || targetType.equals(long.class)) {
                return construct(LongConverter.class);
            }
            return null;
        } catch (Exception e) {
            throw new IllegalArgumentException("Uventet feil ved konstruksjon av konverter for " + targetType);
        }
    }

    private static <T> Converter<T> construct(Class<? extends Converter<T>> clazz)
        throws NoSuchMethodException, IllegalAccessException, InvocationTargetException, InstantiationException {
        return clazz.getDeclaredConstructor().newInstance();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()
            + "[cluster=" + cluster
            + ", namespace=" + namespace
            + ", propertySources=" + propertySources
            + "]";
    }
}
