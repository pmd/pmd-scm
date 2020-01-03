package TestPackage
  package EmptyPackage
  end EmptyPackage;

  package Constants
    constant Integer Answer = 42;
  end Constants;
  
  model Test
    Modelica.Blocks.Interaction.Show.RealValue realValue annotation(
      Placement(visible = true, transformation(origin = {70, 14}, extent = {{-10, -10}, {10, 10}}, rotation = 0)));
    Modelica.Blocks.Continuous.Integrator integrator annotation(
      Placement(visible = true, transformation(origin = {0, 14}, extent = {{-10, -10}, {10, 10}}, rotation = 0)));
  equation
    connect(integrator.y, realValue.numberPort) annotation(
      Line(points = {{12, 14}, {58, 14}}, color = {0, 0, 127}));
    integrator.u = time;
  annotation(
      experiment(StartTime = 0, StopTime = 1, Tolerance = 1e-6, Interval = 0.002));end Test;

annotation(
    uses(Modelica(version = "3.2.3")));

end TestPackage;