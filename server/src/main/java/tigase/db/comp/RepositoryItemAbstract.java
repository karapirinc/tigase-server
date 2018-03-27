/*
 * RepositoryItemAbstract.java
 *
 * Tigase Jabber/XMPP Server
 * Copyright (C) 2004-2017 "Tigase, Inc." <office@tigase.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 */

package tigase.db.comp;

import tigase.kernel.BeanUtils;
import tigase.kernel.DefaultTypesConverter;
import tigase.kernel.TypesConverter;
import tigase.kernel.beans.config.ConfigField;
import tigase.server.Command;
import tigase.server.Packet;
import tigase.xml.Element;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Created: Sep 23, 2010 6:53:14 PM
 *
 * @author <a href="mailto:artur.hefczyc@tigase.org">Artur Hefczyc</a>
 * @version $Rev$
 */
public abstract class RepositoryItemAbstract
		implements RepositoryItem {

	public static final String ADMINS_ATT = "admins";

	public static final String ADMINS_LABEL = "Administrators";

	public static final String OWNER_ATT = "owner";

	public static final String OWNER_LABEL = "Owner";

	private static final TypesConverter typesConverter = new DefaultTypesConverter();

	private String[] admins = null;
	private String owner = null;

	public abstract String getElemName();

	protected abstract void setKey(String key);

	@Override
	public void addCommandFields(Packet packet) {
		Command.addFieldValue(packet, OWNER_LABEL,
							  (owner != null) ? owner : packet.getStanzaTo().getBareJID().toString());
		Command.addFieldValue(packet, ADMINS_LABEL, adminsToString(admins));
	}

	@Override
	public String[] getAdmins() {
		return admins;
	}

	@Override
	public void setAdmins(String[] admins) {
		this.admins = admins;
	}

	@Override
	public String getOwner() {
		return owner;
	}

	@Override
	public void setOwner(String owner) {
		this.owner = owner;
	}

	@Override
	public void initFromCommand(Packet packet) {
		owner = Command.getFieldValue(packet, OWNER_LABEL);
		if ((owner == null) || owner.trim().isEmpty()) {
			owner = packet.getStanzaFrom().getBareJID().toString();
		}
		admins = adminsFromString(Command.getFieldValue(packet, ADMINS_LABEL));
	}

	@Override
	public void initFromElement(Element elem) {
		owner = elem.getAttributeStaticStr(OWNER_ATT);
		admins = adminsFromString(elem.getAttributeStaticStr(ADMINS_ATT));
	}

	@Override
	public void initFromMap(String key, Map<String, Object> props) {
		initFromMap(key, props, null);
	}

	protected void initFromMap(String key, Map<String, Object> props, BiConsumer<String, Object> additionalOptionsConsumer) {
		setKey(key);
		Map<String, Field> propToField = streamConfigFields().collect(Collectors.toMap(
				field -> Optional.ofNullable(field.getAnnotation(ConfigField.class))
						.map(ConfigField::alias)
						.map(alias -> alias.isEmpty() ? null : alias)
						.orElse(field.getName()), Function.identity()));

		propToField.entrySet().stream().filter(e -> props.containsKey(e.getKey())).forEach(e -> {
			try {
				Field field = e.getValue();
				Object value = props.get(e.getKey());

				Type expType = BeanUtils.getGetterSetterMethodsParameterType(field);
				Class clazz = null;
				if (expType != null) {
					if (expType instanceof Class) {
						clazz = (Class) expType;
					} else if (expType instanceof ParameterizedType) {
						Type type = ((ParameterizedType) expType).getRawType();
						if (type instanceof Class) {
							clazz = (Class) type;
						} else {
							expType = null;
						}
					} else{
						expType = null;
					}
				}

				if (expType != null) {
					value = typesConverter.convert(value, clazz, expType);
				} else {
					value = typesConverter.convert(value, field.getType(), field.getGenericType());
				}

				BeanUtils.setValue(this, e.getValue(), value);
			} catch (Exception ex) {
				throw new RuntimeException("Could not set value for key: " + e.getKey() + " to field: " + e.getValue(), ex);
			}
		});
		
		if (additionalOptionsConsumer != null) {
			props.entrySet()
					.stream()
					.filter(e -> !propToField.containsKey(e.getKey()))
					.forEach(e -> additionalOptionsConsumer.accept(e.getKey(), e.getValue()));
		}
	}

	@Override
	public boolean isAdmin(String id) {
		if (admins == null) {
			return false;
		}
		for (String admin : admins) {
			if (admin.equals(id)) {
				return true;
			}
		}

		return false;
	}

	@Override
	public boolean isOwner(String id) {
		return ((owner == null) ? false : owner.equals(id));
	}

	@Override
	public Element toElement() {
		Element elem = new Element(getElemName());

		if (owner != null) {
			elem.addAttribute(OWNER_ATT, owner);
		}
		if (admins != null) {
			elem.addAttribute(ADMINS_ATT, adminsToString(admins));
		}

		return elem;
	}

	@Override
	public Map<String, Object> toMap() {
		Map<String, Object> props = new HashMap<>();
		streamConfigFields().forEach(field -> {
			try {
				String key = Optional.ofNullable(field.getAnnotation(ConfigField.class))
						.map(ConfigField::alias)
						.map(alias -> alias.isEmpty() ? null : alias)
						.orElse(field.getName());
				Object value = BeanUtils.getValue(this, field);
				if (value != null) {
					props.put(key, value);
				}
			} catch (Exception ex) {
				if (ex instanceof NullPointerException || ex.getCause() instanceof NullPointerException) {
					// if there is a NPE it may be caused by value being not set.
					// Let's assume that it is not set and threat it as a null
					// (value not set to the other value than default).
					return;
				}
				throw new RuntimeException("Could not read data from field: " + field, ex);
			}
		});
		return props;
	}

	private String[] adminsFromString(String admins_m) {
		String[] result = null;

		if ((admins_m != null) && (admins_m.trim().length() > 0)) {
			String[] tmp = admins_m.split(",");

			result = new String[tmp.length];
			for (int i = 0; i < tmp.length; i++) {
				result[i] = tmp[i].trim();
			}
		}

		return result;
	}

	private String adminsToString(String[] admins_m) {
		StringBuilder sb = new StringBuilder(100);

		if ((admins_m != null) && (admins_m.length > 0)) {
			for (String adm : admins_m) {
				if (sb.length() == 0) {
					sb.append(adm);
				} else {
					sb.append(',').append(adm);
				}
			}
		}

		return sb.toString();
	}

	private Stream<Field> streamConfigFields() {
		return Arrays.stream(BeanUtils.getAllFields(getClass()))
				.filter(field -> field.isAnnotationPresent(ConfigField.class));
	}
}

