/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * Copyright (C) 2018 - 2020 QuPath developers, The University of Edinburgh
 * %%
 * QuPath is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * QuPath is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License 
 * along with QuPath.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.lib.measurements;

import java.util.Objects;

/**
 * Factory for creating new Measurement objects.
 * <p>
 * Made more sense when dynamic measurements were in use.
 * <p>
 * May not be particularly useful now (?).
 * 
 * @author Pete Bankhead
 *
 */
class MeasurementFactory {
	
	/**
	 * Create a measurement with a double value.
	 * @param name
	 * @param value
	 * @return
	 */
	public static Measurement createMeasurement(final String name, final double value) {
		return new DoubleMeasurement(name, value);
	}

	/**
	 * Create a measurement with a float value.
	 * @param name
	 * @param value
	 * @return
	 */
	public static Measurement createMeasurement(final String name, final float value) {
		return new FloatMeasurement(name, (float)value);
	}

	static boolean isEqual(Measurement m1, Measurement m2) {
		if (m1 == m2)
			return true;
		return Objects.equals(m1.getName(), m2.getName()) && m1.getValue() == m2.getValue();
	}

	static int hash(Measurement m) {
		return Objects.hash(m.getName(), m.getValue());
	}
	
}

class DoubleMeasurement implements Measurement {
	
	private static final long serialVersionUID = 1L;
	
	private final String name;
	private final double value;
	
	DoubleMeasurement(String name, double value) {
		// There may be many measurements... so interning the names can help considerably
		this.name = name.intern();
		this.value = value;
	}
	
	@Override
	public String getName() {
		return name;
	}

	@Override
	public String toString() {
		return getName() + ": " + getValue();
	}

	@Override
	public double getValue() {
		return value;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o instanceof Measurement that) {
			return MeasurementFactory.isEqual(this, that);
		} else {
			return false;
		}
	}

	@Override
	public int hashCode() {
		return MeasurementFactory.hash(this);
	}
}



class FloatMeasurement implements Measurement {
	
	private static final long serialVersionUID = 1L;
	
	private final String name;
	private final float value;
	
	FloatMeasurement(String name, double value) {
		// There may be many measurements... so interning the names can help considerably
		this.name = name.intern();
		this.value = (float)value;
	}
	
	@Override
	public String getName() {
		return name;
	}

	@Override
	public String toString() {
		return getName() + ": " + getValue();
	}

	@Override
	public double getValue() {
		return value;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o instanceof Measurement that) {
			return MeasurementFactory.isEqual(this, that);
		} else {
			return false;
		}
	}

	@Override
	public int hashCode() {
		return MeasurementFactory.hash(this);
	}

}