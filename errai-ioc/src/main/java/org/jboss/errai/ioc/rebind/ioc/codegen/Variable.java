package org.jboss.errai.ioc.rebind.ioc.codegen;

import javax.enterprise.util.TypeLiteral;

import org.jboss.errai.ioc.rebind.ioc.codegen.meta.MetaClass;

/**
 * 
 * @author Christian Sadilek <csadilek@redhat.com>
 */
public class Variable extends AbstractStatement {
    private String name;
    private MetaClass type;

    public Variable(String name, MetaClass type) {
        this.name = name;
        this.type = type;
    }
    
    public static Variable get(String name, Class type) {
        return new Variable(name, MetaClassFactory.get(type));
    }

    public static Variable get(String name, TypeLiteral type) {
        return new Variable(name, MetaClassFactory.get(type));
    }

    public String getName() {
        return name;
    }

    public MetaClass getType() {
        return type;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((name == null) ? 0 : name.hashCode());
        result = prime * result + ((type == null) ? 0 : type.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        Variable other = (Variable) obj;
        if (name == null) {
            if (other.name != null)
                return false;
        } else if (!name.equals(other.name))
            return false;
        if (type == null) {
            if (other.type != null)
                return false;
        } else if (!type.equals(other.type))
            return false;
        return true;
    }

    @Override
    public String toString() {
        return "Variable [name=" + name + ", type=" + type + "]";
    }

    public String generate() {
        return name;
    }
}
