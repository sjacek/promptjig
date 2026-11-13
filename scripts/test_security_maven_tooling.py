"""The scanned Maven patches must also affect actual warmup, Invoker and standalone execution."""
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

import maven_integration as integration


class MavenToolingPatchesTest(unittest.TestCase):
    def test_rendered_active_realms_keep_catalog_patches_and_the_compatible_plexus_api(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pom = integration.render(root, root / 'maven', root / 'repository', root / 'projects')
            model = ET.parse(pom).getroot()
            versions = integration.versions()
            plugins = model.findall('p:build/p:plugins/p:plugin', integration.NS)
            dependency = next(p for p in plugins if p.findtext('p:artifactId', namespaces=integration.NS) == 'maven-dependency-plugin')
            self.assertEqual(versions['beanutils'], dependency.findtext('p:dependencies/p:dependency/p:version', namespaces=integration.NS))
            resources = next(p for p in model.findall('p:build/p:pluginManagement/p:plugins/p:plugin', integration.NS)
                if p.findtext('p:artifactId', namespaces=integration.NS) == 'maven-resources-plugin')
            self.assertEqual(versions['plexusUtils3'], resources.findtext('p:dependencies/p:dependency/p:version', namespaces=integration.NS))
            self.assertTrue(versions['plexusUtils3'].startswith('3.'), 'Do not remove the host plugin\'s XML API by forcing Plexus 4')

    def test_standalone_example_uses_the_same_reviewed_native_resources_patch(self):
        model = ET.parse(integration.ROOT / 'examples/maven/pom.xml').getroot()
        resources = next(p for p in model.findall('p:build/p:plugins/p:plugin', integration.NS)
            if p.findtext('p:artifactId', namespaces=integration.NS) == 'maven-resources-plugin')
        self.assertEqual(integration.versions()['plexusUtils3'], resources.findtext('p:dependencies/p:dependency/p:version', namespaces=integration.NS))


if __name__ == '__main__':
    unittest.main()
