package dev.stanislav.focusdeck.guard;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import static org.junit.Assert.*;

/** Security boundary regression test: exported recovery must never become public. */
public class ManifestBoundaryTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    @Test public void installedComponentsAreDormantTestOnlyAndPermissionProtected() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Element root = factory.newDocumentBuilder().parse(new File("src/main/AndroidManifest.xml")).getDocumentElement();
        Element queries = (Element) root.getElementsByTagName("queries").item(0);
        Element homeQuery = (Element) queries.getElementsByTagName("intent").item(0);
        assertEquals("android.intent.action.MAIN", ((Element) homeQuery.getElementsByTagName("action").item(0)).getAttributeNS(ANDROID, "name"));
        assertEquals("android.intent.category.HOME", ((Element) homeQuery.getElementsByTagName("category").item(0)).getAttributeNS(ANDROID, "name"));
        Element app = (Element) root.getElementsByTagName("application").item(0);
        assertEquals("true", app.getAttributeNS(ANDROID, "testOnly"));
        assertEquals("false", app.getAttributeNS(ANDROID, "allowBackup"));
        assertEquals("@xml/no_data_extraction", app.getAttributeNS(ANDROID, "dataExtractionRules"));
        NodeList receivers = app.getElementsByTagName("receiver");
        assertEquals(5, receivers.getLength());
        for (int i = 0; i < receivers.getLength(); i++) {
            Element receiver = (Element) receivers.item(i);
            String name = receiver.getAttributeNS(ANDROID, "name");
            if (name.equals(".GuardScheduleReceiver")) {
                assertEquals("false", receiver.getAttributeNS(ANDROID, "exported"));
            } else {
                String permission = name.equals(".AdbRecoveryReceiver") ? "android.permission.DUMP"
                        : name.equals(".WakeRequestReceiver") ? "dev.stanislav.focusdeck.guard.permission.SCHEDULE_WAKE"
                        : "android.permission.BIND_DEVICE_ADMIN";
                assertEquals(permission, receiver.getAttributeNS(ANDROID, "permission"));
            }
            assertEquals("true", receiver.getAttributeNS(ANDROID, "directBootAware"));
        }
        NodeList permissions = root.getElementsByTagName("uses-permission");
        java.util.Set<String> requested = new java.util.HashSet<>();
        for (int i = 0; i < permissions.getLength(); i++) requested.add(((Element) permissions.item(i)).getAttributeNS(ANDROID, "name"));
        assertEquals(java.util.Set.of("android.permission.HIDE_OVERLAY_WINDOWS",
                "android.permission.RECEIVE_BOOT_COMPLETED", "android.permission.SCHEDULE_EXACT_ALARM"), requested);
        Element narrowPermission = (Element) root.getElementsByTagName("permission").item(0);
        assertEquals("dev.stanislav.focusdeck.guard.permission.SCHEDULE_WAKE", narrowPermission.getAttributeNS(ANDROID, "name"));
        assertEquals("signature", narrowPermission.getAttributeNS(ANDROID, "protectionLevel"));
        assertEquals(0, app.getElementsByTagName("service").getLength());
        assertEquals(0, app.getElementsByTagName("provider").getLength());
        Element policies = factory.newDocumentBuilder().parse(new File("src/main/res/xml/guard_admin.xml")).getDocumentElement();
        assertEquals(0, policies.getElementsByTagName("wipe-data").getLength());
        assertEquals(0, policies.getElementsByTagName("force-lock").getLength());
    }

    @Test public void deviceProtectedRecoveryJournalCannotMigrateToAnotherPhone() throws Exception {
        Element rules = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new File("src/main/res/xml/no_data_extraction.xml")).getDocumentElement();
        for (String transport : new String[] {"cloud-backup", "device-transfer"}) {
            Element section = (Element) rules.getElementsByTagName(transport).item(0);
            assertNotNull(section);
            NodeList exclusions = section.getElementsByTagName("exclude");
            java.util.Set<String> domains = new java.util.HashSet<>();
            for (int i = 0; i < exclusions.getLength(); i++) {
                Element exclusion = (Element) exclusions.item(i);
                assertEquals(".", exclusion.getAttribute("path"));
                domains.add(exclusion.getAttribute("domain"));
            }
            assertTrue(domains.containsAll(java.util.Set.of("root", "file", "database", "sharedpref", "external",
                    "device_root", "device_file", "device_database", "device_sharedpref")));
        }
    }
}
