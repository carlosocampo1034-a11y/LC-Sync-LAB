"""Perfil de iOS (.mobileconfig) con la CA de laboratorio RESTRINGIDA (solo 10.0.0.121 / 127.0.0.1 / lab.invalid).
Sin firmar (iOS lo mostrará como "No verificado"): la autenticidad se comprueba con la huella SHA-256.
El perfil se elimina solo (RemovalDate) a los 7 días. Uso: perfil_ios.py <salida.mobileconfig>"""
import base64
import datetime
import os
import plistlib
import sys
import uuid

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization

D = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "instance", "tls")


def generar(salida, dias=7):
    ca = x509.load_pem_x509_certificate(open(os.path.join(D, "lab_ca.pem"), "rb").read())
    if ca.extensions.get_extension_for_class(x509.NameConstraints) is None:
        sys.exit("la CA no está restringida: no se genera perfil")
    huella = ca.fingerprint(hashes.SHA256()).hex(" ").upper()
    fin = min(datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(days=dias), ca.not_valid_after_utc)
    perfil = {
        "PayloadType": "Configuration", "PayloadVersion": 1,
        "PayloadIdentifier": "lab.lc.prueba.ca", "PayloadUUID": str(uuid.uuid4()).upper(),
        "PayloadDisplayName": "L&C LABORATORIO (temporal)",
        "PayloadOrganization": "L&C laboratorio",
        "PayloadDescription": ("Certificado de la prueba de laboratorio de L&C. Solo es válido para 10.0.0.121 (Wi-Fi de casa); "
                               "no sirve para ningún otro sitio. Se elimina solo el " + fin.strftime("%Y-%m-%d") +
                               ". Para quitarlo antes: Ajustes > General > VPN y gestión de dispositivos > L&C > Eliminar. "
                               "Huella SHA-256: " + huella),
        "PayloadRemovalDisallowed": False,
        "RemovalDate": fin.replace(tzinfo=None),
        "PayloadContent": [{
            "PayloadType": "com.apple.security.root", "PayloadVersion": 1,
            "PayloadIdentifier": "lab.lc.prueba.ca.raiz", "PayloadUUID": str(uuid.uuid4()).upper(),
            "PayloadDisplayName": "L&C LAB CA (solo 10.0.0.121)",
            "PayloadCertificateFileName": "lab_ca.cer",
            "PayloadContent": ca.public_bytes(serialization.Encoding.DER),
        }],
    }
    with open(salida, "xb") as f:                       # nunca sobrescribe
        plistlib.dump(perfil, f, fmt=plistlib.FMT_XML)
    return huella, fin


if __name__ == "__main__":
    h, fin = generar(sys.argv[1])
    print("perfil generado · huella", h, "· se elimina solo", fin.date())
